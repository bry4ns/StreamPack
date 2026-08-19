/* Licensed under the Apache License, Version 2.0 */
package io.github.thibaultbee.streampack.core.elements.processing.video

/** Thread-safe values consumed by the video fragment shader. */
class VideoFilterState {
    @Volatile var brightnessEnabled = false
    @Volatile var brightness = 0f
    @Volatile var contrastEnabled = false
    @Volatile var contrast = 1f
    @Volatile var saturationEnabled = false
    @Volatile var saturation = 1f
    @Volatile var temperatureEnabled = false
    @Volatile var temperature = 0f
    @Volatile var sharpnessEnabled = false
    @Volatile var sharpness = 0f
    @Volatile var monochromeEnabled = false
    @Volatile var monochrome = 0f
    @Volatile var sepiaEnabled = false
    @Volatile var sepia = 0f
    @Volatile var cinematicEnabled = false
    @Volatile var cinematic = 0f

    fun reset() {
        brightnessEnabled = false; brightness = 0f
        contrastEnabled = false; contrast = 1f
        saturationEnabled = false; saturation = 1f
        temperatureEnabled = false; temperature = 0f
        sharpnessEnabled = false; sharpness = 0f
        monochromeEnabled = false; monochrome = 0f
        sepiaEnabled = false; sepia = 0f
        cinematicEnabled = false; cinematic = 0f
    }

    fun uniformValues(): Map<String, Float> = mapOf(
        "uFilterBrightness" to if (brightnessEnabled) brightness else 0f,
        "uFilterContrast" to if (contrastEnabled) contrast else 1f,
        "uFilterSaturation" to if (saturationEnabled) saturation else 1f,
        "uFilterTemperature" to if (temperatureEnabled) temperature else 0f,
        "uFilterSharpness" to if (sharpnessEnabled) sharpness else 0f,
        "uFilterMonochrome" to if (monochromeEnabled) monochrome else 0f,
        "uFilterSepia" to if (sepiaEnabled) sepia else 0f,
        "uFilterCinematic" to if (cinematicEnabled) cinematic else 0f,
    )
}
