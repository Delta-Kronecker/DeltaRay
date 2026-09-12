package com.leadaxe.lxbox

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button

/// DeltaRay: экран выбора между двумя приложениями в одном APK.
///
/// LauncherActivity — единственная entry point из home-launcher'а
/// (MAIN + LAUNCHER в манифесте). Каждая кнопка открывает полное приложение;
/// возврат на этот экран — обычным Back (эта activity остаётся в task ниже).
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        findViewById<Button>(R.id.btn_open_lxbox).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btn_open_zerodpi).setOnClickListener {
            startActivity(Intent(this, dev.zerodpi.android.MainActivity::class.java))
        }
    }
}