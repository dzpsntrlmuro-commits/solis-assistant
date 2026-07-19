package com.temiztube.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.temiztube.app.data.AdBlockFilter
import com.temiztube.app.data.YoutubeRepository
import com.temiztube.app.databinding.ActivityFilmSearchBinding
import java.io.ByteArrayInputStream
import java.net.URLEncoder

/**
 * Film / video search results via Google Video tab.
 * UI never labels the provider; ads/trackers are filtered.
 */
@SuppressLint("SetJavaScriptEnabled")
class FilmSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilmSearchBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilmSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty().trim()
        binding.filmTitle.text = query
        binding.filmBackButton.setOnClickListener { finish() }

        if (query.isBlank()) {
            finish()
            return
        }

        setupWebView()
        binding.filmWebView.loadUrl(buildVideoSearchUrl(query))

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.filmWebView.canGoBack()) {
                        binding.filmWebView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun setupWebView() {
        val wv = binding.filmWebView
        wv.setBackgroundColor(0xFF0A1619.toInt())
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = false
        wv.settings.displayZoomControls = false
        wv.settings.mediaPlaybackRequiresUserGesture = false

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.filmLoading.isVisible = newProgress in 1..89
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (url.isNotBlank() && AdBlockFilter.shouldBlock(url)) {
                    return EMPTY
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                return handleNavigation(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleNavigation(url.orEmpty())
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.filmLoading.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.filmLoading.isVisible = false
                view?.evaluateJavascript(CLEANUP_JS, null)
            }
        }
    }

    private fun handleNavigation(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()

        // Open YouTube / youtu.be in our player
        if (lower.contains("youtube.com/watch") ||
            lower.contains("youtu.be/") ||
            lower.contains("youtube.com/shorts/") ||
            lower.contains("m.youtube.com/watch")
        ) {
            val id = YoutubeRepository.extractVideoId(url)
            if (!id.isNullOrBlank()) {
                startActivity(
                    Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_URL, "https://www.youtube.com/watch?v=$id")
                        putExtra(PlayerActivity.EXTRA_TITLE, binding.filmTitle.text.toString())
                        putExtra(PlayerActivity.EXTRA_UPLOADER, "Murovideo")
                    }
                )
                return true
            }
        }

        // Stay inside video search / google result pages
        return false
    }

    override fun onDestroy() {
        binding.filmWebView.stopLoading()
        binding.filmWebView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUERY = "extra_query"

        private val EMPTY = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )

        fun buildVideoSearchUrl(query: String): String {
            val encoded = URLEncoder.encode(query, "UTF-8")
            // tbm=vid → video results only (films & clips)
            return "https://www.google.com/search?hl=tr&gl=tr&tbm=vid&q=$encoded"
        }

        // Soften chrome / cookie / promo noise without naming the engine in our UI
        private const val CLEANUP_JS = """
            (function(){
              try {
                var css = document.createElement('style');
                css.innerHTML = `
                  #taw, #bottomads, .commercial-unit-desktop-rhs, .ads-ad,
                  [data-text-ad], .uEierd, #consent-bump, .VzNShc,
                  .JzueNb, .yl3Rkc, g-raised-button, .Kp52if,
                  .appbar, #lb, .RvQfNf { display:none !important; }
                  body { background:#0A1619 !important; }
                `;
                document.documentElement.appendChild(css);
                document.querySelectorAll('button, a').forEach(function(el){
                  var t = (el.textContent||'').toLowerCase();
                  if (t.indexOf('kabul')>=0 || t.indexOf('accept')>=0 || t.indexOf('agree')>=0) {
                    try { el.click(); } catch(e){}
                  }
                });
              } catch(e) {}
            })();
        """
    }
}
