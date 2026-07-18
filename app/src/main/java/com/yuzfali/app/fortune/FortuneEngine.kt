package com.yuzfali.app.fortune

import com.yuzfali.app.model.FaceTraits
import com.yuzfali.app.model.FortuneScript
import kotlin.random.Random

object FortuneEngine {

    fun generate(traits: FaceTraits): FortuneScript {
        val rng = Random(traits.moodSeed.toLong())

        val emotionalCore = when {
            traits.smile >= 0.55f -> pick(
                rng,
                "ışık taşıyan",
                "umutla beslenen",
                "içten parlayan",
                "yumuşak ama dirençli"
            )
            traits.smile <= 0.2f -> pick(
                rng,
                "derin düşünen",
                "sessizce güçlenen",
                "duygularını biriktiren",
                "kendi fırtınasını yöneten"
            )
            else -> pick(
                rng,
                "denge arayan",
                "yavaşça açılan",
                "kalbinden dinleyen",
                "dönüşüme hazır"
            )
        }

        val gaze = ((traits.leftEyeOpen + traits.rightEyeOpen) / 2f)
        val visionLine = when {
            gaze >= 0.7f -> pick(
                rng,
                "Gözlerindeki açıklık, yakında netleşecek bir seçimi haber veriyor.",
                "Bakışın uyanık; önündeki fırsatlar seni sessizce çağırıyor.",
                "Gözlerinde merak var — merak ise yeni kapıları çalıyor."
            )
            gaze <= 0.35f -> pick(
                rng,
                "Bakışın içe dönük; bu dönem seni kendi merkezine çekiyor.",
                "Gözlerin dinleniyor gibi — aslında ruhun yeniden hizalanıyor.",
                "İçe kapanışın bir son değil; duygusal bir yenilenme eşiği."
            )
            else -> pick(
                rng,
                "Bakışın dengeli; hem dış dünyayı hem iç sesini duyuyorsun.",
                "Gözlerinde hem temkin hem cesaret var — ikisi de sana hizmet edecek.",
                "Orta yol senin gücün; acele etmeden ilerliyorsun."
            )
        }

        val lifePath = when {
            traits.headYaw > 8f -> pick(
                rng,
                "Yüzünün hafif yönelişi, yakın zamanda farklı bir yola sapacağını söylüyor.",
                "Sıradan rutinden çıkacak küçük bir karar, büyük bir kapı açacak.",
                "Sağa sola bakınman boşa değil; alternatif bir yol seni bekliyor."
            )
            traits.headYaw < -8f -> pick(
                rng,
                "Geçmişle bağın henüz tamamen kopmamış; ama bundan güç alacaksın.",
                "Geride bıraktığın bir hikâye, önündeki seçimi aydınlatacak.",
                "Eski bir duygu geri dönebilir — bu kez sen yöneteceksin."
            )
            else -> pick(
                rng,
                "Doğrudan bakışın, kararlarında netleşeceğini gösteriyor.",
                "Önündeki yol düzleşiyor; kararsızlık yerini sakin bir güvene bırakacak.",
                "Merkezinde duruyorsun — bu, büyük bir istikrar döneminin habercisi."
            )
        }

        val love = when {
            traits.smile >= 0.5f -> pick(
                rng,
                "Duygusal alanda sıcak bir yakınlaşma seni bekliyor; kalbin açıldıkça bağlar güçlenecek.",
                "Sevgi konusunda cömert olacağın bir dönemdesin; verdiğin enerji geri dönecek.",
                "İlişkilerinde yumuşaklık artacak; bir gülümsemen bile köprü kuracak."
            )
            traits.smile <= 0.25f -> pick(
                rng,
                "Kalbin temkinli ama hazır; doğru kişi sabırla yaklaştığında duvarların inecek.",
                "Duygusal yalnızlık hissi geçici; içindeki boşluk yakında anlamlı bir bağla dolacak.",
                "Kendine dönmen, sonra daha sağlıklı sevmen için gerekli bir durak."
            )
            else -> pick(
                rng,
                "Aşk ve dostluk arasında yeni bir denge kuracaksın; samimiyet ön planda olacak.",
                "Duyguların dalgalansa da kalbin doğru ritmi bulacak.",
                "Bir yakınlık ya derinleşecek ya da yerini daha dürüst bir bağa bırakacak."
            )
        }

        val career = when {
            traits.faceWidthRatio >= 0.42f -> pick(
                rng,
                "Varlığın güçlü; iş ve üretim alanında görünürlüğün artacak.",
                "Sorumluluk almak sana yakışacak; bir proje senin adınla anılacak.",
                "Maddi alanda istikrar kapıda; emeklerinin karşılığını göreceksin."
            )
            else -> pick(
                rng,
                "İnce detaylara hâkimiyetin, kariyerinde seni öne çıkaracak.",
                "Küçük adımlarla ilerlesen de sonuçlar büyük olacak.",
                "Öğrenme ve uyum yeteneğin, yeni bir alanda seni hızla yükseltecek."
            )
        }

        val nearFuture = pick(
            rng,
            "Önümüzdeki haftalarda duygusal bir netlik gelecek; ne istediğini daha keskin hissedeceksin.",
            "Yakın gelecekte bir haber veya rastlantı, yönünü değiştirecek kadar etkili olacak.",
            "Kısa sürede iç sesin yükselacak; onu takip ettiğinde pişman olmayacaksın.",
            "Önünde kısa bir duraksama var; ardından daha hafif ve kararlı ilerleyeceksin."
        )

        val closing = pick(
            rng,
            "Unutma: yüzün kaderini yazmaz; ama bugünkü hâlin, yarının kapısını aralıyor.",
            "Bu fal bir ayna — sen değiştikçe yansıma da değişir.",
            "Sesini dinle. En doğru kehanet, kendi kalbinde duruyor.",
            "Işığın sende. Yol, sen yürüdükçe belirginleşecek."
        )

        val intro = "Yüzünde $emotionalCore bir enerji okuyorum. $visionLine"
        val body1 = "$lifePath $love"
        val body2 = "$career $nearFuture"
        val outro = closing

        val headline = when {
            traits.smile >= 0.55f -> "Parlak bir açılış"
            traits.smile <= 0.2f -> "Derin bir dönüşüm"
            gaze >= 0.7f -> "Net bir bakış"
            else -> "Sakin bir uyanış"
        }

        return FortuneScript(
            headline = headline,
            paragraphs = listOf(intro, body1, body2, outro)
        )
    }

    private fun pick(rng: Random, vararg options: String): String =
        options[rng.nextInt(options.size)]
}
