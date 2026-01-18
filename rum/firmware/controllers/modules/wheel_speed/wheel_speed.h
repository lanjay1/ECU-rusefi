#pragma once

#include "controllers/core/engine_module.h"

/*
 * WheelSpeed module skeleton
 * - Call wheel_speed_isr_pulse(timestampUs) from ICU input-capture ISR
 * - The module computes a simple speed (kph) based on pulse interval and a configurable circumference
 */

class WheelSpeed : public EngineModule {
public:
	WheelSpeed();

	void initNoConfiguration() override;
	void onConfigurationChange(engine_configuration_s const* previousConfig) override;
	void onSlowCallback() override;

	static void wheelSpeedOnPulse(uint32_t timestampUs);
	static float getLastSpeedKph();

private:
	static WheelSpeed* s_instance;
	uint32_t lastPulseUs{0};
	float lastSpeedKph{0.0f};
	float tireCircumferenceMeters{1.9f}; // set via config if available
};