package com.yuzfali.app.engine

import com.yuzfali.app.model.AnalysisSnapshot
import com.yuzfali.app.model.FaceMetrics
import com.yuzfali.app.model.FortuneReport
import com.yuzfali.app.model.PoseMetrics
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

object FortuneEngine {

    fun generate(snapshot: AnalysisSnapshot): FortuneReport {
        val seed = snapshot.fingerprint?.features?.contentHashCode()?.toLong()
            ?: snapshot.face.frameCount.toLong()
        val rng = Random(seed)
        val scores = computeScores(snapshot.face, snapshot.pose)

        val faceSection = buildFaceReading(snapshot.face, scores, rng)
        val postureSection = buildPostureReading(snapshot.pose, scores, rng)
        val emotionSection = buildEmotionReading(snapshot.face, snapshot.pose, scores)
        val futureSection = buildFutureReading(snapshot.pose, scores, rng)

        val fullSpeech = buildString {
            append("Yüz falı raporunuz hazır. ")
            append(faceSection)
            append(" ")
            append(postureSection)
            append(" ")
            append(emotionSection)
            append(" ")
            append(futureSection)
        }

        return FortuneReport(
            faceSection = faceSection,
            postureSection = postureSection,
            emotionSection = emotionSection,
            futureSection = futureSection,
            fullSpeech = fullSpeech
        )
    }

    fun refreshLiveSections(snapshot: AnalysisSnapshot, stored: FortuneReport): FortuneReport {
        val scores = computeScores(snapshot.face, snapshot.pose)
        val postureSection = buildPostureReading(snapshot.pose, scores, Random(snapshot.pose.frameCount.toLong()))
        val emotionSection = buildEmotionReading(snapshot.face, snapshot.pose, scores)
        return stored.withLiveSections(
            emotionSection = emotionSection,
            postureSection = postureSection
        )
    }

    private data class AnalysisScores(
        val warmth: Int,
        val focus: Int,
        val balance: Int,
        val confidence: Int,
        val vitality: Int,
        val introspection: Int,
        val expressiveness: Int
    )

    private fun computeScores(face: FaceMetrics, pose: PoseMetrics): AnalysisScores {
        val warmth = (face.smileProbability * 55f + face.smileRange * 35f + 10f).roundToInt().coerceIn(8, 98)
        val focus = (
            (face.leftEyeOpen + face.rightEyeOpen) / 2f * 40f +
                (1f - abs(face.headEulerY) / 35f).coerceIn(0f, 1f) * 35f +
                face.eyeSymmetry * 25f
            ).roundToInt().coerceIn(8, 98)

        val balance = (
            face.eyeSymmetry * 40f +
                (1f - face.landmarkAsymmetry * 4f).coerceIn(0f, 1f) * 35f +
                (1f - abs(face.headEulerZ) / 25f).coerceIn(0f, 1f) * 25f
            ).roundToInt().coerceIn(8, 98)

        val confidence = if (pose.frameCount == 0) {
            (balance * 0.6f + warmth * 0.4f).roundToInt()
        } else {
            (
                (1f - abs(pose.shoulderTilt) / 18f).coerceIn(0f, 1f) * 35f +
                    (1f - pose.spineAngle / 28f).coerceIn(0f, 1f) * 30f +
                    (1f - pose.headOffset * 3f).coerceIn(0f, 1f) * 20f +
                    pose.postureStability * 15f
                ).roundToInt().coerceIn(8, 98)
        }

        val vitality = ((warmth + focus + confidence) / 3f).roundToInt().coerceIn(8, 98)
        val introspection = (
            (1f - face.smileProbability) * 30f +
                abs(face.headEulerY) / 30f * 25f +
                face.expressionVolatility * 20f +
                pose.spineAngle / 30f * 25f
            ).roundToInt().coerceIn(8, 98)

        val expressiveness = (face.smileRange * 70f + face.expressionVolatility * 30f)
            .roundToInt().coerceIn(8, 98)

        return AnalysisScores(warmth, focus, balance, confidence, vitality, introspection, expressiveness)
    }

    private fun buildFaceReading(face: FaceMetrics, scores: AnalysisScores, rng: Random): String {
        val smilePct = (face.smileProbability * 100).roundToInt()
        val parts = mutableListOf<String>()

        parts += when {
            scores.warmth >= 75 -> "Gülümseme yoğunluğunuz yüzde $smilePct ölçüldü; sıcak ve ulaşılabilir bir enerji yaydığınız görülüyor."
            scores.warmth >= 45 -> "İfade dengeniz yüzde $smilePct gülümseme ile ölçüldü; ciddiyet ve neşeyi aynı anda taşıyorsunuz."
            else -> "Yüz ifadeniz yüzde $smilePct gülümseme ile daha içe dönük okunuyor; derin düşünce hâliniz baskın."
        }

        val shape = when {
            face.faceRatio > 0.88f -> "geniş ve belirgin"
            face.faceRatio < 0.72f -> "ince ve uzun"
            else -> "oval ve dengeli"
        }
        parts += "Yüz oranınız ${formatRatio(face.faceRatio)} olarak ölçüldü; $shape bir yapı sizi tanımlıyor."

        if (face.eyeDistanceRatio > 0f) {
            val eyeSpan = when {
                face.eyeDistanceRatio > 0.42f -> "geniş göz aralığınız merak ve açık bakışı simgeliyor"
                face.eyeDistanceRatio < 0.34f -> "dar göz aralığınız yoğun odaklanmayı gösteriyor"
                else -> "göz aralığınız ortalama değerlerde; ölçülü bir bakışınız var"
            }
            parts += "${eyeSpan.capitalizeFirst()}."
        }

        if (face.noseToMouthRatio > 0f) {
            val mouthHint = when {
                face.noseToMouthRatio > 0.62f -> "Burun-dudak mesafeniz uzun; sabırlı ve stratejik düşünürsünüz."
                face.noseToMouthRatio < 0.48f -> "Burun-dudak mesafeniz kısa; hızlı karar alan bir yapınız var."
                else -> "Burun-dudak oranınız dengeli; söz ve eylem arasında uyum kurarsınız."
            }
            parts += mouthHint
        }

        val symmetryPct = (face.eyeSymmetry * 100).roundToInt()
        parts += if (scores.balance >= 70) {
            "Yüz simetriniz yüzde $symmetryPct; duygusal denge gücünüz yüksek."
        } else {
            "Hafif asimetri yüzde ${(face.landmarkAsymmetry * 100).roundToInt()} ölçüldü; duygusal derinliğiniz güçlü."
        }

        val headNote = when {
            abs(face.headEulerY) > 12f -> "Başınız ${abs(face.headEulerY).roundToInt()} derece yana eğik; yaratıcı ve sorgulayan bir zihin."
            abs(face.headEulerZ) > 8f -> "Baş eğiminiz ${abs(face.headEulerZ).roundToInt()} derece; inatçı ama kararlı bir karakter."
            else -> "Baş pozisyonunuz dik; net ve odaklı bir duruş sergiliyorsunuz."
        }
        parts += headNote

        val extras = listOf(
            "Dudak genişliğiniz göz mesafenize göre ${describeRatio(face.mouthWidthRatio, 0.95f, 1.15f)}.",
            "Elmacık hattınız ${describeRatio(face.cheekWidthRatio, 1.3f, 1.7f)}.",
            "İfade değişkenliğiniz yüzde ${(face.expressionVolatility * 100).roundToInt()}; ${if (scores.expressiveness > 60) "duygularınızı yüzünüzden okumak kolay" else "duygularınızı içten yaşarsınız"}."
        )
        parts += extras[rng.nextInt(extras.size)]

        return parts.joinToString(" ")
    }

    private fun buildPostureReading(pose: PoseMetrics, scores: AnalysisScores, rng: Random): String {
        if (pose.frameCount == 0) {
            return "Duruş verisi bu taramada sınırlı kaldı; omuz ve üst gövde net görünmediği için yüz analizi ağırlıklı rapor oluşturuldu."
        }

        val parts = mutableListOf<String>()
        val tilt = pose.shoulderTilt.roundToInt()
        val spine = pose.spineAngle.roundToInt()
        val stabilityPct = (pose.postureStability * 100).roundToInt()

        parts += when {
            abs(pose.shoulderTilt) < 4f -> "Omuz hattınız neredeyse tam düz (${tilt}°); özgüven skorunuz yüzde ${scores.confidence}."
            pose.shoulderTilt > 4f -> "Sağ omuzunuz ${tilt}° daha yüksek; koruyucu ve sorumluluk alan bir duruş."
            else -> "Sol omuzunuz ${abs(tilt)}° öne çıkıyor; yaratıcı ve sezgisel enerji baskın."
        }

        parts += when {
            pose.spineAngle < 7f -> "Omurga açınız ${spine}° ile dik; fiziksel dayanıklılığınız güçlü."
            pose.spineAngle < 16f -> "Omurga eğiminiz ${spine}° ile rahat; esnek ve uyumlu bir duruşunuz var."
            else -> "Omurga eğiminiz ${spine}° ile belirgin; yoğun zihinsel yük taşıyor olabilirsiniz."
        }

        val headAlignPct = ((1f - pose.headOffset.coerceIn(0f, 0.35f) / 0.35f) * 100).roundToInt()
        parts += if (headAlignPct > 70) {
            "Baş-omuz hizanız yüzde $headAlignPct; odaklanma kapasiteniz yüksek."
        } else {
            "Baş pozisyonunuz omuz merkezinden kaymış; yeni yönlere açık bir enerji okunuyor."
        }

        parts += "Tarama boyunca duruş istikrarınız yüzde $stabilityPct ölçüldü."

        val extras = listOf(
            "Omuz genişliği ölçümünüz bu kadrajda ${pose.shoulderWidth.roundToInt()} piksel referansıyla kaydedildi.",
            "Duruş güven skorunuz yüzde ${(pose.confidence * 100).roundToInt()}; ${if (pose.confidence > 0.7f) "net ve kararlı bir beden dili" else "daha yumuşak bir beden dili"} sergiliyorsunuz."
        )
        parts += extras[rng.nextInt(extras.size)]

        return parts.joinToString(" ")
    }

    private fun buildEmotionReading(face: FaceMetrics, pose: PoseMetrics, scores: AnalysisScores): String {
        val energy = scores.vitality
        val stability = scores.balance
        val leftEyePct = (face.leftEyeOpen * 100).roundToInt()
        val rightEyePct = (face.rightEyeOpen * 100).roundToInt()

        val mood = when {
            scores.warmth > 70 && stability > 65 -> "açık, sıcak ve dengeli"
            scores.warmth < 35 && scores.introspection > 60 -> "içe dönük ve hassas"
            face.leftEyeOpen < 0.45f || face.rightEyeOpen < 0.45f -> "yorgun ama dirençli"
            scores.expressiveness > 65 -> "canlı ve değişken"
            scores.confidence > 65 -> "kendinden emin ve sakin"
            else -> "geçiş ve dönüşüm içinde"
        }

        val eyeNote = if (abs(face.leftEyeOpen - face.rightEyeOpen) > 0.15f) {
            "Sol göz açıklığı yüzde $leftEyePct, sağ göz yüzde $rightEyePct; duygusal yükünüz tek tarafa yığılmış olabilir."
        } else {
            "Göz açıklığınız iki tarafta da dengeli (sol $leftEyePct, sağ $rightEyePct)."
        }

        val postureMood = if (pose.frameCount > 0) {
            when {
                pose.spineAngle > 16f -> "Bedeninizde hafif çökme var; dinlenme ihtiyacı sinyali güçlü."
                abs(pose.shoulderTilt) > 8f -> "Omuzlarınızdaki gerginlik duygusal yükü yansıtıyor olabilir."
                else -> "Beden duruşunuz duygusal olarak destekleyici."
            }
        } else {
            "Üst gövde verisi sınırlı; duygu okuması yüz ifadesine dayanıyor."
        }

        return buildString {
            append("Anlık duygusal durumunuz $mood. ")
            append("Enerji yüzde $energy, duygusal istikrar yüzde $stability. ")
            append(eyeNote)
            append(" ")
            append(postureMood)
            append(" ")
            append("Gülümseme aralığınız tarama boyunca yüzde ${(face.smileRange * 100).roundToInt()} değişti; ")
            append(if (face.smileRange > 0.2f) "ifadeniz canlı ve hareketli." else "ifadeniz kontrollü ve sabit.")
        }
    }

    private fun buildFutureReading(
        pose: PoseMetrics,
        scores: AnalysisScores,
        rng: Random
    ): String {
        val fortuneScore = (
            scores.warmth * 0.22f +
                scores.focus * 0.18f +
                scores.balance * 0.18f +
                scores.confidence * 0.22f +
                scores.vitality * 0.20f
            ).roundToInt().coerceIn(35, 97)

        val timing = listOf(
            "önümüzdeki 6 hafta içinde",
            "yaz aylarına girerken",
            "sonbahar döneminde",
            "yılın son çeyreğinde",
            "yakın zamanda"
        )[rng.nextInt(5)]

        val careerPool = listOf(
            "iş veya eğitim alanında beklenmedik bir teklif",
            "uzun süredir ertelediğiniz bir projenin hızlanması",
            "yeni bir iş birliği veya ortaklık",
            "yeteneğinizin fark edilmesi",
            "finansal açıdan rahatlatıcı bir gelişme"
        )
        val lovePool = listOf(
            "ilişkilerde daha açık iletişim",
            "yeni bir tanışıklık veya mevcut bağın derinleşmesi",
            "aile içinde barış ve yakınlaşma",
            "duygusal olarak kendinizi daha özgür hissetme",
            "kalp konularında net bir karar verme dönemi"
        )
        val healthPool = listOf(
            "düzenli uyku ve hareket size çok iyi gelecek",
            "stres yönetimi önceliğiniz olmalı",
            "fiziksel enerjiniz yükselişte; bu tempoyu koruyun",
            "boyun ve omuz bölgesine dikkat etmeniz faydalı",
            "beslenme düzeninize küçük bir iyileştirme büyük fark yaratır"
        )

        val career = careerPool[(scores.confidence + rng.nextInt(3)) % careerPool.size]
        val love = lovePool[(scores.warmth + rng.nextInt(3)) % lovePool.size]
        val health = if (pose.frameCount > 0 && pose.spineAngle > 15f) {
            healthPool[3]
        } else {
            healthPool[(scores.vitality + rng.nextInt(3)) % healthPool.size]
        }

        return "Gelecek öngörüsü: Fal skorunuz $fortuneScore/100. $timing $career kapınızı çalabilir. Aşk ve ilişkilerde $love bekleniyor. Sağlık açısından $health."
    }

    private fun formatRatio(value: Float): String =
        if (value <= 0f) "ölçülemedi" else String.format("%.2f", value)

    private fun describeRatio(value: Float, low: Float, high: Float): String = when {
        value <= 0f -> "bu taramada net ölçülemedi"
        value < low -> "ortalamanın altında"
        value > high -> "ortalamanın üstünde"
        else -> "ortalama bandında"
    }

    private fun String.capitalizeFirst(): String =
        if (isEmpty()) this else replaceFirstChar { it.uppercase() }
}
