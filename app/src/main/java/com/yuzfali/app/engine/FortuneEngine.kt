package com.yuzfali.app.engine

import com.yuzfali.app.model.AnalysisSnapshot
import com.yuzfali.app.model.FaceMetrics
import com.yuzfali.app.model.FortuneReport
import com.yuzfali.app.model.PoseMetrics
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

object FortuneEngine {

    fun generate(snapshot: AnalysisSnapshot, scanNonce: Long): FortuneReport {
        val profile = buildProfile(snapshot.face, snapshot.pose)
        val rng = Random(profile.signature xor scanNonce)

        return FortuneReport(
            faceSection = buildFaceReading(snapshot.face, profile, rng),
            postureSection = buildPostureReading(snapshot.pose, profile, rng),
            emotionSection = buildEmotionReading(snapshot.face, snapshot.pose, profile),
            futureSection = buildFutureReading(snapshot.face, snapshot.pose, profile, rng)
        )
    }

    private data class MetricProfile(
        val signature: Long,
        val warmth: Int,
        val focus: Int,
        val balance: Int,
        val confidence: Int,
        val vitality: Int,
        val introspection: Int,
        val expressiveness: Int,
        val destinyNumber: Int
    )

    private fun buildProfile(face: FaceMetrics, pose: PoseMetrics): MetricProfile {
        val warmth = pct(face.smileProbability * 0.7f + face.smileRange * 0.3f, 0f, 1f)
        val focus = pct(
            (face.leftEyeOpen + face.rightEyeOpen) / 2f * 0.55f +
                (1f - abs(face.headEulerY) / 40f).coerceIn(0f, 1f) * 0.25f +
                face.eyeSymmetry * 0.2f,
            0f, 1f
        )
        val balance = pct(
            face.eyeSymmetry * 0.45f +
                (1f - face.landmarkAsymmetry * 3.5f).coerceIn(0f, 1f) * 0.35f +
                (1f - abs(face.headEulerZ) / 30f).coerceIn(0f, 1f) * 0.2f,
            0f, 1f
        )
        val confidence = if (pose.frameCount == 0) {
            ((balance + warmth) / 2f).roundToInt()
        } else {
            pct(
                (1f - abs(pose.shoulderTilt) / 20f).coerceIn(0f, 1f) * 0.35f +
                    (1f - pose.spineAngle / 30f).coerceIn(0f, 1f) * 0.3f +
                    (1f - pose.headOffset * 2.5f).coerceIn(0f, 1f) * 0.2f +
                    pose.postureStability * 0.15f,
                0f, 1f
            )
        }
        val vitality = ((warmth + focus + confidence) / 3f).roundToInt()
        val introspection = pct(
            (1f - face.smileProbability) * 0.35f +
                abs(face.headEulerY) / 35f * 0.25f +
                face.expressionVolatility * 0.2f +
                pose.spineAngle / 35f * 0.2f,
            0f, 1f
        )
        val expressiveness = pct(face.smileRange * 0.65f + face.expressionVolatility * 0.35f, 0f, 1f)

        val destinyNumber = (
            face.smileProbability * 137f +
                face.eyeDistanceRatio * 311f +
                face.noseToMouthRatio * 197f +
                face.mouthWidthRatio * 223f +
                face.cheekWidthRatio * 179f +
                face.landmarkAsymmetry * 251f +
                face.leftEyeOpen * 89f +
                face.rightEyeOpen * 97f +
                abs(face.headEulerY) * 3.1f +
                abs(face.headEulerZ) * 2.7f +
                pose.shoulderTilt * 1.9f +
                pose.spineAngle * 2.3f +
                pose.headOffset * 401f +
                pose.postureStability * 167f
            ).roundToInt().let { (it % 900) + 100 }

        val signature = mix(
            face.smileProbability,
            face.leftEyeOpen,
            face.rightEyeOpen,
            face.eyeDistanceRatio,
            face.noseToMouthRatio,
            face.mouthWidthRatio,
            face.cheekWidthRatio,
            face.landmarkAsymmetry,
            face.headEulerY,
            face.headEulerZ,
            pose.shoulderTilt,
            pose.spineAngle,
            pose.headOffset,
            pose.postureStability
        )

        return MetricProfile(
            signature = signature,
            warmth = warmth,
            focus = focus,
            balance = balance,
            confidence = confidence,
            vitality = vitality,
            introspection = introspection,
            expressiveness = expressiveness,
            destinyNumber = destinyNumber
        )
    }

    private fun buildFaceReading(face: FaceMetrics, profile: MetricProfile, rng: Random): String {
        val smilePct = dec(face.smileProbability * 100f)
        val parts = mutableListOf<String>()

        parts += when {
            profile.warmth >= 78 -> "Gülümseme yoğunluğunuz %$smilePct; sıcak ve davetkâr bir ifade okunuyor."
            profile.warmth >= 52 -> "Gülümseme seviyeniz %$smilePct; neşe ile ciddiyet arasında dengeli bir çizgi."
            profile.warmth >= 30 -> "İfadeniz %$smilePct gülümseme ile ölçüldü; düşünceli ve mesafeli bir hava var."
            else -> "Yüz ifadeniz %$smilePct gülümseme ile içe dönük; derin odak ve gözlem gücü öne çıkıyor."
        }

        parts += when {
            face.faceRatio > 0.90f -> "Yüz genişliği/yüksekliği oranı ${dec(face.faceRatio)} — belirgin ve güçlü bir yüz hatları yapısı."
            face.faceRatio < 0.74f -> "Yüz oranınız ${dec(face.faceRatio)} — ince, uzun ve sezgisel bir profil."
            else -> "Yüz oranınız ${dec(face.faceRatio)} — dengeli oval bir form."
        }

        if (face.eyeDistanceRatio > 0f) {
            parts += when {
                face.eyeDistanceRatio > 0.43f -> "Göz aralığı oranı ${dec(face.eyeDistanceRatio)}; meraklı ve keşfedici bir bakış."
                face.eyeDistanceRatio < 0.33f -> "Göz aralığı oranı ${dec(face.eyeDistanceRatio)}; yoğun konsantrasyon ve detay odaklı zihin."
                else -> "Göz aralığı oranı ${dec(face.eyeDistanceRatio)}; ölçülü ve analitik bir bakış."
            }
        }

        if (face.noseToMouthRatio > 0f) {
            parts += when {
                face.noseToMouthRatio > 0.64f -> "Burun-dudak oranı ${dec(face.noseToMouthRatio)}; sabırlı, planlı karar alırsınız."
                face.noseToMouthRatio < 0.47f -> "Burun-dudak oranı ${dec(face.noseToMouthRatio)}; hızlı refleks ve ani kararlar."
                else -> "Burun-dudak oranı ${dec(face.noseToMouthRatio)}; düşünce ve eylem uyumu güçlü."
            }
        }

        if (face.mouthWidthRatio > 0f) {
            parts += "Dudak genişliği oranı ${dec(face.mouthWidthRatio)}; " + when {
                face.mouthWidthRatio > 1.12f -> "iletişim gücünüz yüksek."
                face.mouthWidthRatio < 0.92f -> "sözlerinizi ölçülü kullanırsınız."
                else -> "ifade netliğiniz dengeli."
            }
        }

        parts += "Göz simetriniz %${(face.eyeSymmetry * 100).roundToInt()}, yüz asimetrisi ${dec(face.landmarkAsymmetry * 100f)} puan."

        val headY = abs(face.headEulerY)
        val headZ = abs(face.headEulerZ)
        parts += when {
            headY > 14f -> "Baş yönünüz Y ekseninde ${dec(headY)}° eğimli; yaratıcı sorgulama enerjisi."
            headZ > 10f -> "Baş eğiminiz Z ekseninde ${dec(headZ)}°; kararlı ve dirençli yapı."
            else -> "Baş açılarınız Y ${dec(headY)}°, Z ${dec(headZ)}°; odaklı ve dengeli duruş."
        }

        val cheekLines = listOf(
            "Elmacık hattı oranı ${dec(face.cheekWidthRatio)}; sosyal çevrede fark edilirsiniz.",
            "İfade değişkenliği %${(face.expressionVolatility * 100).roundToInt()}; duygularınız ${if (profile.expressiveness > 55) "yüzünüze hızlı yansır" else "içten yaşanır"}.",
            "Gülümseme aralığınız %${(face.smileRange * 100).roundToInt()}; ${if (face.smileRange > 0.18f) "canlı mimikler" else "kontrollü ifade"} sergilersiniz."
        )
        parts += cheekLines[(profile.warmth + rng.nextInt(3)) % cheekLines.size]

        return parts.joinToString(" ")
    }

    private fun buildPostureReading(pose: PoseMetrics, profile: MetricProfile, rng: Random): String {
        if (pose.frameCount == 0) {
            return "Omuz ve üst gövde bu taramada net okunamadı; rapor yüz ölçümlerine göre oluşturuldu."
        }

        val parts = mutableListOf<String>()
        val tilt = dec(pose.shoulderTilt)
        val spine = dec(pose.spineAngle)

        parts += when {
            abs(pose.shoulderTilt) < 3.5f -> "Omuz hattı neredeyse düz (${tilt}°); özgüven skoru %${profile.confidence}."
            pose.shoulderTilt > 3.5f -> "Sağ omuz ${tilt}° yüksekte; koruyucu ve sorumluluk alan duruş."
            else -> "Sol omuz ${dec(abs(pose.shoulderTilt))}° önde; sezgisel ve yaratıcı enerji baskın."
        }

        parts += when {
            pose.spineAngle < 6.5f -> "Omurga açısı ${spine}° ile dik; dayanıklılık yüksek."
            pose.spineAngle < 15f -> "Omurga eğimi ${spine}° ile rahat; esnek ve uyumlu."
            else -> "Omurga eğimi ${spine}° ile belirgin; zihinsel yük veya yorgunluk sinyali."
        }

        val alignPct = pct(1f - pose.headOffset.coerceIn(0f, 0.4f) / 0.4f, 0f, 1f)
        parts += "Baş-omuz hizası %$alignPct, duruş istikrarı %${(pose.postureStability * 100).roundToInt()}."

        val extras = listOf(
            "Omuz genişliği ölçümü ${pose.shoulderWidth.roundToInt()} px referans; beden dili ${if (pose.confidence > 0.72f) "net" else "yumuşak"}.",
            "Duruş güveni %${(pose.confidence * 100).roundToInt()}; ${pose.frameCount} kare boyunca analiz edildi.",
            "Postür skorunuz %${profile.confidence}; omuz ve omurga verileri birlikte değerlendirildi."
        )
        parts += extras[(profile.confidence + rng.nextInt(5)) % extras.size]

        return parts.joinToString(" ")
    }

    private fun buildEmotionReading(face: FaceMetrics, pose: PoseMetrics, profile: MetricProfile): String {
        val leftEye = (face.leftEyeOpen * 100).roundToInt()
        val rightEye = (face.rightEyeOpen * 100).roundToInt()

        val mood = when {
            profile.warmth > 72 && profile.balance > 68 -> "açık, sıcak ve dengeli"
            profile.warmth < 32 && profile.introspection > 62 -> "içe dönük ve hassas"
            face.leftEyeOpen < 0.42f || face.rightEyeOpen < 0.42f -> "yorgun ama dirençli"
            profile.expressiveness > 68 -> "canlı ve değişken"
            profile.confidence > 68 -> "kendinden emin ve sakin"
            profile.introspection > 60 -> "derin düşünce içinde"
            else -> "duygusal geçiş döneminde"
        }

        val bodyNote = when {
            pose.frameCount == 0 -> "Beden verisi sınırlı; duygu okuması yüze dayanıyor."
            pose.spineAngle > 17f -> "Omurga eğimi duygusal yükü artırıyor olabilir."
            abs(pose.shoulderTilt) > 9f -> "Omuz gerginliği stres sinyali veriyor."
            pose.postureStability < 0.45f -> "Hareketli tarama; duygusal dalgalanma yüksek."
            else -> "Beden duruşunuz duygusal dengeyi destekliyor."
        }

        return buildString {
            append("Anlık ruh hâliniz $mood. ")
            append("Enerji %${profile.vitality}, denge %${profile.balance}, içe dönüklük %${profile.introspection}. ")
            append("Sol göz %$leftEye, sağ göz %$rightEye açıklığında. ")
            append(bodyNote)
            append(" ")
            append("Gülümseme min %${(face.smileMin * 100).roundToInt()}, max %${(face.smileMax * 100).roundToInt()}.")
        }
    }

    private fun buildFutureReading(
        face: FaceMetrics,
        pose: PoseMetrics,
        profile: MetricProfile,
        rng: Random
    ): String {
        val fortuneScore = (
            profile.warmth * 0.21f +
                profile.focus * 0.19f +
                profile.balance * 0.18f +
                profile.confidence * 0.22f +
                profile.vitality * 0.20f
            ).roundToInt().coerceIn(34, 97)

        val timings = listOf(
            "önümüzdeki 3-5 hafta içinde",
            "yaz aylarına yaklaşırken",
            "sonbahar başında",
            "yılın son çeyreğinde",
            "bu ayın ikinci yarısında",
            "yakın gelecekte"
        )
        val timing = timings[pick(profile.destinyNumber, timings.size, rng.nextInt(9))]

        val careers = listOf(
            "iş veya eğitimde beklenmedik bir teklif",
            "ertelediğiniz bir projenin hızlanması",
            "yeni bir iş birliği fırsatı",
            "yeteneğinizin fark edilmesi",
            "finansal rahatlama getiren bir gelişme",
            "yeni bir beceri öğrenme dönemi",
            "terfi veya sorumluluk artışı",
            "girişim veya yan proje için uygun zaman",
            "eski bir bağlantıdan gelen fırsat"
        )
        val loves = listOf(
            "ilişkilerde daha açık iletişim",
            "yeni bir tanışıklık",
            "mevcut bağın derinleşmesi",
            "aile içinde yakınlaşma",
            "duygusal özgürlük hissi",
            "kalp konusunda net karar",
            "eski bir hesaplaşmanın kapanması",
            "sürpriz bir romantik gelişme",
            "kendinize zaman ayırmanın ilişkinize iyi gelmesi"
        )
        val healths = listOf(
            "düzenli uyku ve yürüyüş size iyi gelecek",
            "stres yönetimi öncelik olmalı",
            "enerjiniz yükselişte; tempoyu koruyun",
            "boyun ve omuzlara dikkat edin",
            "beslenmede küçük düzenleme büyük fark yaratır",
            "su tüketimini artırmanız faydalı",
            "kısa molalar vererek verim artacak",
            "esneme hareketleri rahatlatacak",
            "dijital ekran molası ihtiyacı var"
        )

        val careerIdx = pick(profile.confidence + profile.destinyNumber, careers.size, face.noseToMouthRatio.roundToInt())
        val loveIdx = pick(profile.warmth + profile.destinyNumber, loves.size, (face.smileProbability * 100).roundToInt())
        val healthIdx = when {
            pose.frameCount > 0 && pose.spineAngle > 16f -> 3
            profile.vitality < 40 -> 1
            profile.vitality > 75 -> 2
            else -> pick(profile.vitality + profile.destinyNumber, healths.size, rng.nextInt(11))
        }

        val career = careers[careerIdx]
        val love = loves[loveIdx]
        val health = healths[healthIdx]

        val tone = when {
            fortuneScore > 82 -> "Güçlü bir dönem sizi bekliyor."
            fortuneScore > 65 -> "Olumlu gelişmeler mümkün."
            fortuneScore > 48 -> "Sabırla ilerlerseniz kapılar açılır."
            else -> "Zorlayıcı ama öğretici bir süreç."
        }

        return buildString {
            append("Kader numaranız ${profile.destinyNumber}, fal skoru $fortuneScore/100. ")
            append("$timing $career ile karşılaşabilirsiniz. ")
            append("Aşk ve ilişkilerde $love öngörülüyor. ")
            append("Sağlık: $health. ")
            append(tone)
        }
    }

    private fun pick(base: Int, size: Int, salt: Int): Int =
        abs(base * 17 + salt * 31) % size

    private fun pct(value: Float, min: Float, max: Float): Int {
        if (max <= min) return 50
        return ((value - min) / (max - min) * 100f).roundToInt().coerceIn(0, 100)
    }

    private fun dec(value: Float): String = String.format("%.1f", value)

    private fun mix(vararg values: Float): Long {
        var hash = 17L
        for (value in values) {
            hash = hash * 31L + value.toBits().toLong()
        }
        return hash
    }
}
