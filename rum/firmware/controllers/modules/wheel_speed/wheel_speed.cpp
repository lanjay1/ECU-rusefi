#include "wheel_speed.h"
#include "efi_printf.h"

WheelSpeed* WheelSpeed::s_instance = nullptr;

WheelSpeed::WheelSpeed() {
	s_instance = this;
}

void WheelSpeed::initNoConfiguration() {
	lastPulseUs = 0;
	lastSpeedKph = 0.0f;
}

void WheelSpeed::onConfigurationChange(engine_configuration_s const* /*previousConfig*/) {
	// load tire circumference or pulses-per-rev from config if you added fields
}

void WheelSpeed::onSlowCallback() {
	// expose lastSpeedKph to telemetry if desired
}

void WheelSpeed::wheelSpeedOnPulse(uint32_t timestampUs) {
	if (!s_instance) return;

	if (s_instance->lastPulseUs == 0) {
		s_instance->lastPulseUs = timestampUs;
		return;
	}
	uint32_t dtUs = timestampUs - s_instance->lastPulseUs;
	s_instance->lastPulseUs = timestampUs;
	if (dtUs == 0) return;

	float hz = 1e6f / (float)dtUs;
	float mps = hz * s_instance->tireCircumferenceMeters;
	s_instance->lastSpeedKph = mps * 3.6f;

	efiPrintf("WheelSpeed: dtUs=%u speed=%.2f kph\n", dtUs, s_instance->lastSpeedKph);
}

float WheelSpeed::getLastSpeedKph() {
	return s_instance ? s_instance->lastSpeedKph : 0.0f;
}

// C wrapper for ISR
extern "C" void wheel_speed_isr_pulse(uint32_t timestampUs) {
	WheelSpeed::wheelSpeedOnPulse(timestampUs);
}