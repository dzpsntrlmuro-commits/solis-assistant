package com.macsans.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.macsans.app.R
import com.macsans.app.api.FootballApiClient
import com.macsans.app.data.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val input = findViewById<EditText>(R.id.inputApiKey)
        val status = findViewById<TextView>(R.id.txtKeyStatus)
        input.setText(ApiKeyStore.get(this))
        status.text = if (ApiKeyStore.hasKey(this)) {
            "Kayıtlı anahtar var (gizli). Kaydet / Test et ile doğrula."
        } else {
            "Henüz anahtar yok. Ücretsiz hesap açıp anahtarı yapıştır."
        }

        findViewById<TextView>(R.id.btnBackSettings).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnOpenDashboard).setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://dashboard.api-football.com/")
                )
            )
        }

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val key = input.text?.toString().orEmpty().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "Anahtar boş olamaz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            status.text = "Doğrulanıyor…"
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    FootballApiClient(key).validateKey()
                }
                if (result.first) {
                    ApiKeyStore.save(this@SettingsActivity, key)
                    status.text = result.second
                    Toast.makeText(this@SettingsActivity, "Anahtar kaydedildi", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                } else {
                    status.text = "Hata: ${result.second}"
                    Toast.makeText(this@SettingsActivity, "Anahtar geçersiz", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btnClearKey).setOnClickListener {
            ApiKeyStore.save(this, "")
            input.setText("")
            status.text = "Anahtar silindi."
            setResult(RESULT_OK)
        }
    }
}
