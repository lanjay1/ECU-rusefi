/*
 * STM32F4 IRQ handlers forwarding to isr_glue.
 *
 * This file assumes:
 *  - Quick-shifter is wired to an EXTI line whose IRQ maps to EXTI0_IRQHandler (adjust if using other EXTI).
 *  - Wheel-speed input capture is configured on TIM2 CH1 and TIM2 IRQ is enabled.
 *
 * You must configure EXTI and TIM2 capture in board init (board_configuration.cpp or board init code).
 * The handlers below are intentionally minimal: read flags/CCR and forward to glue.
 *
 * NOTE: If you choose different EXTI lines or timers, replace IRQ handler names accordingly.
 */

#include "isr_glue.h"
#include "stm32f4xx.h" // CMSIS device header (STM32F4)
#include <stdint.h>

/*
 * EXTI0 IRQ handler: handles EXTI line 0 pending bit.
 * If your quick-shifter pin is on another EXTI line (1..15), provide the corresponding handler
 * (EXTI1_IRQHandler, EXTI2_IRQHandler, EXTI3_IRQHandler, EXTI9_5_IRQHandler, EXTI15_10_IRQHandler).
 */
void EXTI0_IRQHandler(void) {
    // Check pending bit for EXTI0
    if ((EXTI->PR & EXTI_PR_PR0) != 0U) {
        // Clear pending by writing 1
        EXTI->PR = EXTI_PR_PR0;

        // Call minimal glue (fast)
        quick_shifter_exti_handler();
    }
}

/*
 * TIM2 IRQ handler: capture on CH1 used for wheel-speed input.
 * Reads CCR1 (assumes timer configured so ticks ~ microseconds) and clears CC1IF.
 *
 * If you use another TIM or channel, adapt this handler accordingly.
 */
void TIM2_IRQHandler(void) {
    uint32_t sr = TIM2->SR;

    // Capture/compare 1
    if (sr & TIM_SR_CC1IF) {
        uint32_t ccr = TIM2->CCR1;
        // clear CC1IF by writing 0 to the bit (write 0 is fine after read)
        TIM2->SR &= ~TIM_SR_CC1IF;

        // Forward timestamp (ticks). If timer tick != 1us convert before calling.
        // Here we forward raw CCR value assuming timer was set to 1MHz (1 tick = 1 us).
        wheel_speed_timer_capture_handler_us(ccr);
    }

    // Other TIM2 interrupts (update, CC2, etc) are ignored here.
}