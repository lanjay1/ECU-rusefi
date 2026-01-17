#pragma once

#include "controllers/core/engine_module.h"
#include "global.h"

/*
 * QuickShifter module skeleton
 *
 * Hook points:
 * - Call quick_shifter_isr_pulse() from your GPIO/ICU ISR when A1124 toggles.
 * - Implement/bridge requestIgnitionCutMs(uint16_t ms) weak symbol to perform actual cut.
 */

class QuickShifter : public EngineModule {
public:
	QuickShifter();

	void initNoConfiguration() override;
	void onConfigurationChange(engine_configuration_s const* previousConfig) override;
	void onFastCallback() override;

	// Called from ISR wrapper
	static void quickShifterOnPulse();

	static QuickShifter* instance();

private:
	bool enabled{false};
	uint32_t lastPulseTimeMs{0};
	uint16_t cutMsDefault{20};
};

extern "C" void requestIgnitionCutMs(uint16_t ms) __attribute__((weak));