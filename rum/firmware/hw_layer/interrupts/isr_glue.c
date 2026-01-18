#include "isr_glue.h"

// Module-level C wrappers expected to exist in your modules.
// quick_shifter_isr_pulse() should be extremely short (set atomic flag).
// wheel_speed_isr_pulse(timestampUs) should be extremely short (queue timestamp).
extern void quick_shifter_isr_pulse(void);
extern void wheel_speed_isr_pulse(uint32_t timestampUs);

/*
 * These functions are intended for ISR context: they must be very short and
 * only forward to the module-level wrappers. Do NOT perform heavy processing here.
 */

void quick_shifter_exti_handler(void) {
    // Forward directly to module wrapper
    quick_shifter_isr_pulse();
}

void wheel_speed_timer_capture_handler_us(uint32_t timestampUs) {
    // Forward captured timestamp (in microseconds)
    wheel_speed_isr_pulse(timestampUs);
}