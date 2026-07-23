package com.yuzfali.app.engine

import com.yuzfali.app.model.AnalysisSnapshot
import com.yuzfali.app.model.FaceMetrics
import com.yuzfali.app.model.FortuneReport
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

object FortuneEngine {

    fun generate(snapshot: AnalysisSnapshot, scanNonce: Long): FortuneReport {
        val face = snapshot.face
        val profile = buildProfile(face)
        val rng = Random(profile.signature xor scanNonce)

        return FortuneReport(
            gazeSection = buildGazeReading(face, profile),
            faceSection = buildFaceReading(face, profile, rng),
            emotionSection = buildEmotionReading(face, profile),
            futureSection = buildFutureReading(face, profile, rng)
        )
    }

    private data class MetricProfile(
        val signature: Long,
        val gazeIntensity: Int,
        val eyeOpenness: Int,
        val eyeBalance: Int,
        val warmth: Int,
        val mystery: Int,
        val expressiveness: Int,
        val destinyNumber: Int
    )

    private fun buildProfile(face: FaceMetrics): MetricProfile {
        val eyeOpenness = pct((face.leftEyeOpen + face.rightEyeOpen) / 2f, 0f, 1f)
        val eyeBalance = pct(face.eyeSymmetry, 0f, 1f)
        val gazeIntensity = pct(
            (1f - abs(face.headEulerY) / 40f).coerceIn(0f, 1f) * 0.5f +
                (1f - abs(face.headEulerX) / 35f).coerceIn(0f, 1f) * 0.3f +
                eyeOpenness / 100f * 0.2f,
            0f, 1f
        )
        val warmth = pct(face.smileProbability * 0.75f + face.smileRange * 0.25f, 0f, 1f)
        val mystery = pct(
            (1f - face.smileProbability) * 0.4f +
                abs(face.headEulerY) / 35f * 0.3f +
                face.landmarkAsymmetry * 0.3f,
            0f, 1f
        )
        val expressiveness = pct(face.smileRange * 0.6f + face.expressionVolatility * 0.4f, 0f, 1f)

        val destinyNumber = (
            face.smileProbability * 137f +
                face.eyeDistanceRatio * 311f +
                face.leftEyeOpen * 173f +
                face.rightEyeOpen * 181f +
                abs(face.headEulerY) * 4.7f +
                abs(face.headEulerX) * 3.9f +
                face.landmarkAsymmetry * 241f +
                face.noseToMouthRatio * 197f
            ).roundToInt().let { (it % 900) + 100 }

        val signature = mix(
            face.smileProbability,
            face.leftEyeOpen,
            face.rightEyeOpen,
            face.eyeDistanceRatio,
            face.headEulerY,
            face.headEulerX,
            face.landmarkAsymmetry,
            face.noseToMouthRatio
        )

        return MetricProfile(
            signature = signature,
            gazeIntensity = gazeIntensity,
            eyeOpenness = eyeOpenness,
            eyeBalance = eyeBalance,
            warmth = warmth,
            mystery = mystery,
            expressiveness = expressiveness,
            destinyNumber = destinyNumber
        )
    }

    private fun buildGazeReading(face: FaceMetrics, profile: MetricProfile): String {
        val leftPct = (face.leftEyeOpen * 100).roundToInt()
        val rightPct = (face.rightEyeOpen * 100).roundToInt()
        val parts = mutableListOf<String>()

        parts += "Bakış yönünüz ${face.gazeHorizontal} ve ${face.gazeVertical} okunuyor."

        parts += when {
            profile.gazeIntensity >= 75 -> "Gözleriniz net ve odaklı; bakış gücünüz yüzde ${profile.gazeIntensity}."
            profile.gazeIntensity >= 45 -> "Bakışınız dengeli ve ölçülü; odak skoru yüzde ${profile.gazeIntensity}."
            else -> "Gözleriniz yumuşak ve derin; içe dönük bir bakış, skor yüzde ${profile.gazeIntensity}."
        }

        parts += when {
            face.eyeDistanceRatio > 0.43f -> "Geniş göz aralığınız (${dec(face.eyeDistanceRatio)}) merak ve keşif arzusunu gösteriyor."
            face.eyeDistanceRatio in 0.01f..0.33f -> "Dar göz aralığınız (${dec(face.eyeDistanceRatio)}) yoğun konsantrasyon ve detay odaklı zihni simgeliyor."
            face.eyeDistanceRatio > 0f -> "Göz aralığınız (${dec(face.eyeDistanceRatio)}) analitik ve ölçülü bir bakışa işaret ediyor."
            else -> "Göz yapınız dikkatli ve seçici bir bakış taşıyor."
        }

        parts += if (abs(face.leftEyeOpen - face.rightEyeOpen) > 0.12f) {
            "Sol göz açıklığı %$leftPct, sağ göz %$rightPct; duygusal yükünüz bakışınıza yansıyor."
        } else {
            "İki gözünüz de dengeli (sol %$leftPct, sağ %$rightPct); bakış simetriniz yüzde ${profile.eyeBalance}."
        }

        parts += when {
            face.headEulerY > 12f -> "Hafif yana bakan gözleriniz geleceğe açık, yeni fikirlere meraklı bir ruhu anlatıyor."
            face.headEulerY < -12f -> "İçe dönük bakışınız derin düşünce ve sezgisel gücü öne çıkarıyor."
            face.headEulerX > 10f -> "Yukarı bakan bakışınız umut ve vizyon dolu bir gelecek arayışını gösteriyor."
            face.headEulerX < -10f -> "Aşağı eğik bakışınız hassasiyet ve içsel sorgulamayı yansıtıyor."
            else -> "Düz ileri bakan gözleriniz kararlılık ve net hedef duygusunu taşıyor."
        }

        return parts.joinToString(" ")
    }

    private fun buildFaceReading(face: FaceMetrics, profile: MetricProfile, rng: Random): String {
        val smilePct = dec(face.smileProbability * 100f)
        val parts = mutableListOf<String>()

        parts += when {
            profile.warmth >= 75 -> "Yüz ifadeniz sıcak; gülümseme yoğunluğu %$smilePct."
            profile.warmth >= 45 -> "İfadeniz dengeli; gülümseme seviyesi %$smilePct."
            else -> "Yüzünüz ciddi ve düşünceli; gülümseme %$smilePct ile ölçüldü."
        }

        parts += when {
            face.faceRatio > 0.90f -> "Yüz oranınız ${dec(face.faceRatio)}; güçlü ve belirgin hatlar."
            face.faceRatio < 0.74f -> "Yüz oranınız ${dec(face.faceRatio)}; ince ve sezgisel bir profil."
            else -> "Yüz oranınız ${dec(face.faceRatio)}; dengeli oval form."
        }

        if (face.noseToMouthRatio > 0f) {
            parts += when {
                face.noseToMouthRatio > 0.64f -> "Burun-dudak oranı ${dec(face.noseToMouthRatio)}; sabırlı ve stratejik karakter."
                face.noseToMouthRatio < 0.47f -> "Burun-dudak oranı ${dec(face.noseToMouthRatio)}; hızlı karar alan yapı."
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

        parts += if (profile.mystery > 60) {
            "Yüzünüzde gizemli bir aura var; her bakışta yeni bir katman okunuyor."
        } else {
            "Yüz ifadeniz açık ve okunaklı; samimiyetiniz güçlü."
        }

        val extras = listOf(
            "Gülümseme aralığınız %${(face.smileRange * 100).roundToInt()}; ${if (profile.expressiveness > 55) "canlı mimikler" else "kontrollü ifade"}.",
            "Yüz asimetrisi ${dec(face.landmarkAsymmetry * 100f)} puan; duygusal derinlik ${if (face.landmarkAsymmetry > 0.08f) "yüksek" else "dengeli"}.",
            "Elmacık hattı oranı ${dec(face.cheekWidthRatio)}; sosyal çekim ${if (face.cheekWidthRatio > 1.5f) "güçlü" else "sakin"}."
        )
        parts += extras[(profile.warmth + rng.nextInt(3)) % extras.size]

        return parts.joinToString(" ")
    }

    private fun buildEmotionReading(face: FaceMetrics, profile: MetricProfile): String {
        val mood = when {
            profile.warmth > 72 && profile.eyeBalance > 65 -> "sıcak, açık ve dengeli"
            profile.warmth < 32 && profile.mystery > 60 -> "içe dönük ve hassas"
            face.leftEyeOpen < 0.42f || face.rightEyeOpen < 0.42f -> "yorgun ama dirençli"
            profile.expressiveness > 68 -> "canlı ve değişken"
            profile.gazeIntensity > 68 -> "odaklı ve kararlı"
            else -> "duygusal geçiş döneminde"
        }

        return buildString {
            append("Gözlerinizden okunan duygu hâli: $mood. ")
            append("Bakış enerjisi yüzde ${profile.gazeIntensity}, yüz sıcaklığı yüzde ${profile.warmth}. ")
            append("Gülümseme min %${(face.smileMin * 100).roundToInt()}, max %${(face.smileMax * 100).roundToInt()}. ")
            append(if (profile.mystery > 55) "Derin bakışlarınız sezgilerinizin güçlü olduğunu gösteriyor." else "Açık bakışınız güven veren bir enerji taşıyor.")
        }
    }

    private fun buildFutureReading(face: FaceMetrics, profile: MetricProfile, rng: Random): String {
        val fortuneScore = (
            profile.gazeIntensity * 0.28f +
                profile.eyeBalance * 0.22f +
                profile.warmth * 0.22f +
                profile.expressiveness * 0.14f +
                (100 - profile.mystery) * 0.14f
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

        val futures = listOf(
            "bakışlarınızın çektiği biri hayatınıza girebilir",
            "gözlerinizin gördüğü bir fırsatı değerlendirme zamanı geliyor",
            "sezgileriniz sizi doğru bir karara yönlendirecek",
            "yüzünüzdeki özgüven yeni kapılar açacak",
            "göz teması kurduğunuz biriyle önemli bir bağ kurulabilir",
            "içten gülümsemeniz çevrenizde olumlu dalgalar yaratacak",
            "bakışlarınızdaki kararlılık kariyerinizde fark yaratacak",
            "gözlerinizin seçiciliği sizi yanlış yoldan koruyacak",
            "yüz ifadenizdeki sıcaklık ilişkilerinizi güçlendirecek"
        )

        val loves = listOf(
            "gözlerinizle kurduğunuz bağ derinleşebilir",
            "bakışlarınız yeni bir aşka işaret ediyor",
            "göz temasıyla kurulan güven ilişkinizi güçlendirecek",
            "içten bir gülümseme kalp kapılarını açacak",
            "sezgisel bakışlarınız doğru kişiyi tanımanızı sağlayacak",
            "duygusal açıklığınız ilişkilerde dönüm noktası getirecek"
        )

        val insights = listOf(
            "gözleriniz geleceği önceden seziyor gibi",
            "bakışlarınızdaki netlik zor günlerde size rehberlik edecek",
            "yüz ifadenizdeki enerji çevrenizi etkilemeye devam edecek",
            "gözlerinizin derinliği gizli yeteneklerinizi ortaya çıkaracak",
            "bakışlarınızdaki umut zorlu dönemleri aşmanıza yardım edecek"
        )

        val future = futures[pick(profile.destinyNumber + profile.gazeIntensity, futures.size, rng.nextInt(7))]
        val love = loves[pick(profile.warmth + profile.eyeBalance, loves.size, rng.nextInt(5))]
        val insight = insights[pick(profile.mystery + profile.destinyNumber, insights.size, rng.nextInt(11))]

        val tone = when {
            fortuneScore > 82 -> "Gözlerinizden güçlü bir dönem okunuyor."
            fortuneScore > 65 -> "Bakışlarınız olumlu gelişmelere işaret ediyor."
            fortuneScore > 48 -> "Sabırlı bakışlarınız meyvesini verecek."
            else -> "İçe dönük döneminiz sizi olgunlaştıracak."
        }

        return buildString {
            append("Kader numaranız ${profile.destinyNumber}, fal skoru $fortuneScore/100. ")
            append("$timing $future. ")
            append("Aşk ve ilişkilerde $love. ")
            append("$insight ")
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
