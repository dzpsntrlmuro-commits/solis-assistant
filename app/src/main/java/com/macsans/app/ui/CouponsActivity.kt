package com.macsans.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macsans.app.R
import com.macsans.app.adapter.CouponAdapter
import com.macsans.app.data.CouponSettler
import com.macsans.app.data.CouponStore
import com.macsans.app.model.CouponStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CouponsActivity : AppCompatActivity() {

    private lateinit var adapter: CouponAdapter
    private lateinit var empty: TextView
    private lateinit var meta: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coupons)

        empty = findViewById(R.id.txtCouponsEmpty)
        meta = findViewById(R.id.txtCouponsMeta)
        adapter = CouponAdapter()
        findViewById<RecyclerView>(R.id.recyclerCoupons).apply {
            layoutManager = LinearLayoutManager(this@CouponsActivity)
            this.adapter = this@CouponsActivity.adapter
        }

        findViewById<TextView>(R.id.btnBackCoupons).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSettle).setOnClickListener { settle() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val list = CouponStore.loadCoupons(this)
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val won = list.count { it.status == CouponStatus.WON }
        val lost = list.count { it.status == CouponStatus.LOST }
        val open = list.count { it.status == CouponStatus.OPEN || it.status == CouponStatus.PARTIAL }
        meta.text = "${list.size} kupon · $open açık · $won tuttu · $lost tutmadı\n" +
            "Güçlü tahmin = yüksek % · Zayıf oran = düşük şans / riskli seçim"
    }

    private fun settle() {
        Toast.makeText(this, "Sonuçlar kontrol ediliyor…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                CouponSettler.settleAll(this@CouponsActivity)
            }
            render()
            Toast.makeText(this@CouponsActivity, "Kuponlar güncellendi", Toast.LENGTH_SHORT).show()
        }
    }
}
