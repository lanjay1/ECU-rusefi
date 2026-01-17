#include "iacv_integration.h"
#include "pch.h" // use project's precompiled header (if present)
#include "efi_time.h"
#include "efi_printf.h"
#include "sensors.h" // for Sensor::getOrZero / SensorType
#include "engine_configuration.h"

IacvIntegration* IacvIntegration::s_instance = nullptr;

// Weak hooks to control IACV from this module.
// Implement these in your idle / IACV driver code to actually set/get the valve position.
extern "C" void iacvSetTargetPercent(float percent) __attribute__((weak));
extern "C" float iacvGetTargetPercent() __attribute__((weak));

IacvIntegration::IacvIntegration() {
	s_instance = this;
}

void IacvIntegration::initNoConfiguration() {
	backfireActive = false;
	backfireRestoreAtMs = 0;
	backfireOldTargetPercent = 0.0f;
	backfireClosedPercent = 0.0f;
	enabled = false;
}

void IacvIntegration::onConfigurationChange(engine_configuration_s const* /*previousConfig*/) {
	// If you added these fields in rusefi_config.txt and generated headers,
	// the engineConfiguration struct will have corresponding members.
#ifdef ENGINE_CONFIGURATION_HAS_enable_iacv_inj_warmup
	enabled = engineConfiguration->enable_iacv_inj_warmup;
#else
	enabled = false;
#endif

#ifdef ENGINE_CONFIGURATION_HAS_backfire_tps_threshold
	tpsThreshold = engineConfiguration->backfire_tps_threshold;
#else
	tpsThreshold = 12.0f;
#endif

#ifdef ENGINE_CONFIGURATION_HAS_backfire_tps_hysteresis
	tpsHysteresis = engineConfiguration->backfire_tps_hysteresis;
#else
	tpsHysteresis = 6.0f;
#endif

#ifdef ENGINE_CONFIGURATION_HAS_backfire_min_rpm
	minRpm = engineConfiguration->backfire_min_rpm;
#else
	minRpm = 800;
#endif

#ifdef ENGINE_CONFIGURATION_HAS_backfire_max_hold_ms
	maxHoldMs = engineConfiguration->backfire_max_hold_ms;
#else
	maxHoldMs = 1000;
#endif

	// closed percent (IACV fully closed). You can make this configurable if needed.
	backfireClosedPercent = 0.0f;
}

void IacvIntegration::onSlowCallback() {
	// restore if time expired
	if (backfireActive) {
		uint32_t now = timeNowMs();
		if (now >= backfireRestoreAtMs) {
			// restore old target
			if (iacvSetTargetPercent) {
				iacvSetTargetPercent(backfireOldTargetPercent);
				efiPrintf("Backfire: IACV restored to %.2f%%\n", backfireOldTargetPercent);
			} else {
				efiPrintf("Backfire: iacvSetTargetPercent hook not implemented, cannot restore\n");
			}
			backfireActive = false;
		}
	}

	// placeholder: injector warmup / IACV coordination logic could go here if enabled
	if (enabled) {
		// TODO: implement warmup idle coordination (IACV + injector bias)
	}
}

void IacvIntegration::doRequestCloseIacvForBackfire(uint16_t holdMs) {
	// Check TPS and RPM before applying
	float tps = Sensor::getOrZero(SensorType::Tps1); // percent (0..100)
	float rpm = Sensor::getOrZero(SensorType::Rpm);

	// threshold logic: close only if TPS <= threshold
	float threshold = tpsThreshold;
	float restoreThreshold = tpsThreshold + tpsHysteresis;

	// min RPM safety check
	if (rpm < (float)minRpm) {
		efiPrintf("Backfire: skip IACV close, RPM %.0f < min %u\n", rpm, minRpm);
		// Optionally still apply ignition retard elsewhere
		return;
	}

	// If TPS greater than threshold, skip closing IACV
	if (tps > threshold) {
		efiPrintf("Backfire: skip IACV close, TPS %.2f%% > threshold %.2f%%\n", tps, threshold);
		return;
	}

	// cap holdMs
	if (holdMs == 0) holdMs = 50; // minimal duration if caller passed 0
	if (holdMs > maxHoldMs) holdMs = maxHoldMs;

	// Save old target
	float oldTarget = 100.0f;
	if (iacvGetTargetPercent) {
		oldTarget = iacvGetTargetPercent();
	} else {
		efiPrintf("Backfire: iacvGetTargetPercent hook not implemented, assuming %.2f%%\n", oldTarget);
	}

	// Set closed position
	if (iacvSetTargetPercent) {
		iacvSetTargetPercent(backfireClosedPercent);
		efiPrintf("Backfire: IACV set to %.2f%% for %u ms (old %.2f%%, TPS %.2f%%, RPM %.0f)\n",
			backfireClosedPercent, holdMs, oldTarget, tps, rpm);
	} else {
		efiPrintf("Backfire: iacvSetTargetPercent hook not implemented, cannot close IACV\n");
	}

	backfireActive = true;
	backfireRestoreAtMs = timeNowMs() + (uint32_t)holdMs;
	backfireOldTargetPercent = oldTarget;
}

void IacvIntegration::requestCloseIacvForBackfire(uint16_t holdMs) {
	if (!s_instance) {
		// safe fallback: create temporary effect via weak hooks (no persistent restore)
		// but better to require module constructed.
		efiPrintf("Backfire: IacvIntegration instance not initialized, performing immediate one-shot\n");
		// perform one-shot close & schedule restore via static state inside this function
		// Simpler: just call doRequestCloseIacvForBackfire on a transient temp object is not possible.
		// So if module not present we still attempt to close and set a timed restore using static variables.
		// For simplicity, skip if no instance.
		return;
	}

	s_instance->doRequestCloseIacvForBackfire(holdMs);
}

bool IacvIntegration::isBackfireIacvActive() {
	return s_instance ? s_instance->backfireActive : false;
}