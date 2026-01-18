#pragma once

#include "controllers/core/engine_module.h"
#include <cstdint>

/*
 * IacvIntegration
 *
 * - Close IACV temporarily for backfire protection (only when TPS low & RPM ok)
 * - Restores previous IACV target after timeout
 *
 * Integration:
 * - Implement weak hooks in your IACV/idle driver:
 *     extern "C" void iacvSetTargetPercent(float percent) { /* call your IACV API */ }
 *     extern "C" float iacvGetTargetPercent() { /* return current IACV target % */ }
 *
 * - Call IacvIntegration::requestCloseIacvForBackfire(ms) from backfire detection
 *   (or from test code). The module will check TPS/RPM and will close & schedule restore.
 */

class IacvIntegration : public EngineModule {
public:
	IacvIntegration();

	void initNoConfiguration() override;
	void onConfigurationChange(engine_configuration_s const* previousConfig) override;
	void onSlowCallback() override;

	// C API: request close IACV for holdMs milliseconds
	static void requestCloseIacvForBackfire(uint16_t holdMs);

	// Helper: return whether IACV currently held closed by backfire logic
	static bool isBackfireIacvActive();

private:
	void doRequestCloseIacvForBackfire(uint16_t holdMs);
	static IacvIntegration* s_instance;

	// state
	bool backfireActive{false};
	uint32_t backfireRestoreAtMs{0};
	float backfireOldTargetPercent{0.0f};
	float backfireClosedPercent{0.0f}; // 0 = fully closed

	// config (populated on onConfigurationChange)
	bool enabled{false};
	float tpsThreshold{12.0f};     // percent
	float tpsHysteresis{6.0f};     // percent
	uint16_t minRpm{800};          // rpm
	uint16_t maxHoldMs{1000};      // ms
};