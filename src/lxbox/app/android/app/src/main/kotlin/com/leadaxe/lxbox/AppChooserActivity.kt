package com.leadaxe.lxbox

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button

/// DeltaRay: скрытый экран выбора между двумя приложениями в одном APK.
/// Сюда попадают только через долгое удержание (10 с) пункта «About»
/// в меню главной страницы лаунчера. Кнопки открывают полное приложение;
/// возврат на главную страницу — обычным Back.
class AppChooserActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_chooser)

        findViewById<Button>(R.id.btn_open_lxbox).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btn_open_zerodpi).setOnClickListener {
            startActivity(Intent(this, dev.zerodpi.android.MainActivity::class.java))
        }
    }
}