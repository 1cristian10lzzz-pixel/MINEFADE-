import kotlin.math.*

plugins {
    id("com.android.application")
}

android {
    namespace = "com.minefade.nativeengine"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.minefade.nativeengine"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "0.6.0-mali-shader-fix"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Wpedantic")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            // A distinct package avoids signature conflicts with older review APKs.
            // Existing installations and their private world data remain untouched.
            applicationIdSuffix = ".review8"
            versionNameSuffix = "-review8"
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    androidResources {
        // SoundPool opens these PCM assets through AssetFileDescriptor, which needs uncompressed assets.
        noCompress += listOf("wav")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE", "META-INF/LICENSE")
        }
    }
}


// Build an original, clean ambient soundtrack into APK assets. It is generated
// locally at build time: no radio, streaming service, music download or encoder.
val generateMinefadeAmbientMusic by tasks.registering {
    val musicFile = layout.projectDirectory.file("src/main/assets/audio/music/overworld.wav").asFile
    outputs.file(musicFile)
    doLast {
        if (!musicFile.parentFile.exists() && !musicFile.parentFile.mkdirs()) {
            throw GradleException("Could not create the Minefade music asset directory")
        }

        val sampleRate = 22050
        val bpm = 72.0
        val segmentSeconds = 16.0 * 60.0 / bpm
        val durationSeconds = segmentSeconds * 4.0
        val frameCount = (durationSeconds * sampleRate).toInt()
        val chords = arrayOf(
            intArrayOf(48, 55, 62, 64, 67, 71), // C major 9
            intArrayOf(45, 52, 55, 59, 64, 69), // A minor 9
            intArrayOf(41, 48, 55, 57, 60, 64), // F major 7
            intArrayOf(43, 50, 57, 60, 62, 67)  // G suspended
        )
        fun midiHz(note: Int): Double = 440.0 * 2.0.pow((note - 69) / 12.0)
        val frequencies = Array(chords.size) { index ->
            DoubleArray(chords[index].size) { k -> midiHz(chords[index][k]) }
        }
        val roots = intArrayOf(36, 33, 29, 31)
        val rootHz = DoubleArray(roots.size) { midiHz(roots[it]) }
        val arpOrder = intArrayOf(0, 2, 3, 4, 5, 3, 2, 1)
        val left = DoubleArray(frameCount)
        val right = DoubleArray(frameCount)
        val fadeSeconds = 1.1
        val beatSeconds = 60.0 / bpm
        val arpStep = beatSeconds * 2.0

        var peak = 0.0
        for (frame in 0 until frameCount) {
            val t = frame.toDouble() / sampleRate
            val pos = t % segmentSeconds
            val chordIndex = (t / segmentSeconds).toInt() % chords.size
            val previousIndex = (chordIndex + chords.size - 1) % chords.size
            val transition = (pos / fadeSeconds).coerceIn(0.0, 1.0)
            val currentWeight = if (pos < fadeSeconds) sin(transition * Math.PI / 2.0) else 1.0
            val previousWeight = if (pos < fadeSeconds) cos(transition * Math.PI / 2.0) else 0.0
            var l = 0.0
            var r = 0.0

            for (k in chords[chordIndex].indices) {
                val f = frequencies[chordIndex][k]
                val phase = 2.0 * Math.PI * f * pos
                val envelope = 0.72 + 0.08 * sin(2.0 * Math.PI * pos / 11.5 + k * 0.63)
                val tone = (sin(phase) + 0.28 * sin(2.0 * phase + 0.2) +
                    0.10 * sin(3.0 * phase + 0.4) +
                    0.34 * sin(2.0 * Math.PI * f * 1.0017 * pos + 0.4 * k)) *
                    (if (k < 4) 0.0105 else 0.0070) * envelope * currentWeight
                val pan = sin(k * 1.31 + chordIndex * 0.37) * 0.48
                l += tone * sqrt((1.0 - pan) * 0.5) * 0.78
                r += tone * sqrt((1.0 + pan) * 0.5) * 0.78

                if (previousWeight > 0.0) {
                    val pf = frequencies[previousIndex][k]
                    val previousLocal = segmentSeconds + pos
                    val pp = 2.0 * Math.PI * pf * previousLocal
                    val previousEnvelope = 0.72 + 0.08 *
                        sin(2.0 * Math.PI * previousLocal / 11.5 + k * 0.63)
                    val previousTone = (sin(pp) + 0.28 * sin(2.0 * pp + 0.2) +
                        0.10 * sin(3.0 * pp + 0.4) +
                        0.34 * sin(2.0 * Math.PI * pf * 1.0017 * previousLocal + 0.4 * k)) *
                        (if (k < 4) 0.0105 else 0.0070) * previousEnvelope * previousWeight
                    val previousPan = sin(k * 1.31 + previousIndex * 0.37) * 0.48
                    l += previousTone * sqrt((1.0 - previousPan) * 0.5) * 0.78
                    r += previousTone * sqrt((1.0 + previousPan) * 0.5) * 0.78
                }
            }

            val root = rootHz[chordIndex]
            val bass = (sin(2.0 * Math.PI * root * pos) +
                0.16 * sin(4.0 * Math.PI * root * pos)) * 0.017 * currentWeight
            l += bass
            r += bass
            if (previousWeight > 0.0) {
                val previousRoot = rootHz[previousIndex]
                val previousLocal = segmentSeconds + pos
                val previousBass = (sin(2.0 * Math.PI * previousRoot * previousLocal) +
                    0.16 * sin(4.0 * Math.PI * previousRoot * previousLocal)) *
                    0.017 * previousWeight
                l += previousBass
                r += previousBass
            }

            val noteIndex = (t / arpStep).toInt()
            val localInStep = t - noteIndex * arpStep
            val arpNote = chords[chordIndex][arpOrder[noteIndex % arpOrder.size]]
            val arpFrequency = midiHz(arpNote) * 2.0
            val arpEnvelope = exp(-localInStep * 1.65) * (1.0 - exp(-localInStep * 22.0))
            val arpPhase = 2.0 * Math.PI * arpFrequency * localInStep
            val pluck = (sin(arpPhase) + 0.23 * sin(2.0 * arpPhase) +
                0.06 * sin(3.0 * arpPhase + 0.3)) * arpEnvelope * 0.008 * currentWeight
            val arpPan = sin(noteIndex * 0.49 + chordIndex * 0.6) * 0.36
            l += pluck * sqrt((1.0 - arpPan) * 0.5)
            r += pluck * sqrt((1.0 + arpPan) * 0.5)

            if (previousWeight > 0.0) {
                val previousArpNote = chords[previousIndex][arpOrder[noteIndex % arpOrder.size]]
                val previousArpFrequency = midiHz(previousArpNote) * 2.0
                val previousArpPhase = 2.0 * Math.PI * previousArpFrequency * localInStep
                val previousPluck = (sin(previousArpPhase) + 0.23 * sin(2.0 * previousArpPhase) +
                    0.06 * sin(3.0 * previousArpPhase + 0.3)) * arpEnvelope * 0.008 * previousWeight
                val previousPan = sin(noteIndex * 0.49 + previousIndex * 0.6) * 0.36
                l += previousPluck * sqrt((1.0 - previousPan) * 0.5)
                r += previousPluck * sqrt((1.0 + previousPan) * 0.5)
            }

            // A barely audible smooth air tone, never hiss or radio static.
            val air = 0.0011 * (0.25 + 0.75 * (0.5 + 0.5 *
                sin(2.0 * Math.PI * t / 17.0 + 1.1))) *
                sin(2.0 * Math.PI * (6200.0 + 30.0 * sin(2.0 * Math.PI * t / 9.0)) * t)
            l += air
            r += air * 0.85

            val edgeSamples = (sampleRate * 0.02).toInt()
            val edge = minOf(1.0, (frame + 1).toDouble() / edgeSamples,
                (frameCount - frame).toDouble() / edgeSamples)
            l *= edge
            r *= edge
            left[frame] = l
            right[frame] = r
            peak = maxOf(peak, abs(l), abs(r))
        }

        val gain = if (peak > 0.000001) 0.78 / peak else 1.0
        val dataBytes = frameCount.toLong() * 4L
        musicFile.outputStream().buffered(64 * 1024).use { out ->
            fun write16(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
            }
            fun write32(value: Long) {
                out.write((value and 0xffL).toInt())
                out.write(((value ushr 8) and 0xffL).toInt())
                out.write(((value ushr 16) and 0xffL).toInt())
                out.write(((value ushr 24) and 0xffL).toInt())
            }
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            write32(36L + dataBytes)
            out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            write32(16L)
            write16(1)
            write16(2)
            write32(sampleRate.toLong())
            write32(sampleRate.toLong() * 4L)
            write16(4)
            write16(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            write32(dataBytes)
            for (frame in 0 until frameCount) {
                val leftPcm = (left[frame] * gain * 32767.0).toInt().coerceIn(-32768, 32767)
                val rightPcm = (right[frame] * gain * 32767.0).toInt().coerceIn(-32768, 32767)
                write16(leftPcm and 0xffff)
                write16(rightPcm and 0xffff)
            }
        }
        logger.lifecycle("Generated Minefade's original 53-second ambient soundtrack: ${musicFile.length()} bytes")
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateMinefadeAmbientMusic)
}
