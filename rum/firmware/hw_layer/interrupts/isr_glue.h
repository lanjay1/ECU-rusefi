#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Minimal glue functions called from board IRQ handlers.
// Implemented in isr_glue.c and call into module-level C wrappers.
void quick_shifter_exti_handler(void);
void wheel_speed_timer_capture_handler_us(uint32_t timestampUs);

#ifdef __cplusplus
}
#endif