package com.yuzfali.app.engine

import com.yuzfali.app.model.AnalysisSnapshot
import com.yuzfali.app.model.FaceMetrics
import com.yuzfali.app.model.FortuneReport
import com.yuzfali.app.model.PoseMetrics
import kotlin.math.abs
import kotlin.math.roundToInt

object FortuneEngine {

    fun generate(snapshot: AnalysisSnapshot): FortuneReport {
        val face = snapshot.face
        val pose = snapshot.pose

        val faceSection = buildFaceReading(face)
        val postureSection = buildPostureReading(pose)
        val emotionSection = buildEmotionReading(face, pose)
        val futureSection = buildFutureReading(face, pose)

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

    private fun buildFaceReading(face: FaceMetrics): String {
        val parts = mutableListOf<String>()

        when {
            face.smileProbability > 0.65f -> parts.add(
                "Geniş alın çizgileriniz ve sıcak gülümsemeniz, içten bir karaktere sahip olduğunuzu gösteriyor."
            )
            face.smileProbability > 0.35f -> parts.add(
                "Yüz hatlarınız dengeli; hem mantıklı hem duygusal kararlar alabilen biri olduğunuzu yansıtıyor."
            )
            else -> parts.add(
                "Derin bakışlarınız ve ciddi ifadeniz, düşünceli ve gözlemci bir ruha işaret ediyor."
            )
        }

        when {
            face.faceRatio > 0.82f -> parts.add("Geniş yüz yapınız sosyal çevrenizde güçlü bir varlık oluşturuyor.")
            face.faceRatio < 0.68f -> parts.add("İnce yüz hatlarınız hassasiyet ve sezgisel zekânızı öne çıkarıyor.")
            else -> parts.add("Oval yüz formunuz uyum ve denge arayışınızı simgeliyor.")
        }

        val headTurn = abs(face.headEulerY)
        when {
            headTurn > 15f -> parts.add("Başınızı hafif eğik tutmanız yaratıcı ve meraklı bir zihni gösteriyor.")
            else -> parts.add("Dik ve kararlı bakışınız liderlik potansiyelinizi ortaya koyuyor.")
        }

        if (face.eyeSymmetry > 0.85f) {
            parts.add("Simetrik göz yapınız iç huzurunuzun güçlü olduğuna işaret ediyor.")
        } else {
            parts.add("Gözlerinizdeki hafif asimetri, derin duygusal dalgalanmalar yaşadığınızı gösteriyor.")
        }

        return parts.joinToString(" ")
    }

    private fun buildPostureReading(pose: PoseMetrics): String {
        if (pose.frameCount == 0) {
            return "Duruş verisi sınırlı; yine de yüz ifadenizden güçlü sinyaller alındı."
        }

        val parts = mutableListOf<String>()

        when {
            abs(pose.shoulderTilt) < 5f -> parts.add(
                "Omuzlarınız dengeli; özgüvenli ve kararlı bir duruş sergiliyorsunuz."
            )
            pose.shoulderTilt > 5f -> parts.add(
                "Sağ omuzunuz hafif yüksek; koruyucu ve sorumluluk alan bir yapınız var."
            )
            else -> parts.add(
                "Sol omuzunuz öne çıkıyor; yaratıcı enerjiniz ve sezgisel gücünüz baskın."
            )
        }

        when {
            pose.spineAngle < 8f -> parts.add("Dik duruşunuz gelecekteki başarılarınız için sağlam bir temel oluşturuyor.")
            pose.spineAngle < 18f -> parts.add("Rahat duruşunuz esnekliğinizi ve uyum yeteneğinizi yansıtıyor.")
            else -> parts.add("Eğik duruşunuz yoğun düşünce süreçlerinden geçtiğinizi gösteriyor; dinlenmeye ihtiyacınız olabilir.")
        }

        when {
            pose.headOffset < 0.08f -> parts.add("Başınız omuzlarınızın tam ortasında; odaklanma gücünüz yüksek.")
            else -> parts.add("Baş pozisyonunuzdaki kayma, yeni yönlere açık olduğunuzu simgeliyor.")
        }

        return parts.joinToString(" ")
    }

    private fun buildEmotionReading(face: FaceMetrics, pose: PoseMetrics): String {
        val energy = ((face.smileProbability + face.eyeSymmetry) / 2f * 100).roundToInt()
        val stability = (100f - abs(pose.shoulderTilt) * 3f - abs(face.headEulerZ))
            .coerceIn(30f, 95f)
            .roundToInt()

        val mood = when {
            face.smileProbability > 0.6f && stability > 70 -> "şu an içsel olarak mutlu ve dengeli"
            face.smileProbability < 0.3f && stability < 55 -> "derin düşüncelere dalmış ve duygusal olarak hassas"
            face.leftEyeOpen < 0.5f || face.rightEyeOpen < 0.5f -> "yorgun ama dirençli bir dönemden geçiyor"
            else -> "duygusal olarak geçiş döneminde, kendini keşfediyor"
        }

        val intensity = when {
            energy > 70 -> "Duygusal enerjiniz yüksek; çevrenize pozitif bir aura yayıyorsunuz."
            energy > 45 -> "Duygusal dengeniz orta seviyede; küçük dokunuşlarla motivasyonunuz artabilir."
            else -> "İç dünyanıza çekildiğiniz bir dönemdesiniz; kendinize zaman ayırmanız önemli."
        }

        return "Duygusal durumunuz: $mood hissediyorsunuz. Enerji seviyeniz yüzde $energy, duygusal istikrarınız yüzde $stability. $intensity"
    }

    private fun buildFutureReading(face: FaceMetrics, pose: PoseMetrics): String {
        val fortuneScore = (
            face.smileProbability * 30 +
                face.eyeSymmetry * 20 +
                (100 - abs(pose.shoulderTilt) * 4).coerceIn(0f, 40f) +
                (40 - pose.spineAngle).coerceIn(0f, 30f)
            ).roundToInt().coerceIn(40, 98)

        val months = listOf("önümüzdeki üç ay", "yaz aylarında", "sonbaharda", "yıl sonuna doğru")
        val monthHint = months[(face.smileProbability * 10).roundToInt() % months.size]

        val career = when {
            fortuneScore > 80 -> "Kariyerinizde beklenmedik bir fırsat kapınızı çalacak."
            fortuneScore > 60 -> "İş hayatınızda istikrarlı bir yükseliş dönemi başlıyor."
            else -> "Sabırlı olduğunuz takdirde yeni bir yol açılacak."
        }

        val love = when {
            face.smileProbability > 0.5f -> "Aşk hayatınızda sıcak ve samimi gelişmeler yaşanacak."
            face.eyeSymmetry > 0.8f -> "Mevcut ilişkinizde derinleşme veya yeni bir tanışıklık sizi bekliyor."
            else -> "Kalbinizi açtığınızda güzel sürprizler sizi bulacak."
        }

        val health = when {
            pose.spineAngle > 15f -> "Sağlığınıza dikkat edin; düzenli hareket size iyi gelecek."
            else -> "Fiziksel enerjiniz güçlü; bu tempo sizi ileri taşıyacak."
        }

        return "Gelecek öngörünüz: Fal skorunuz $fortuneScore üzerinden 100. $monthHint $career $love $health"
    }
}
