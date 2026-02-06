package com.yayahiwo.similarity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Some devices restore a stale Navigation back stack (e.g., the legacy IndexFragment) after
        // process death / app updates. Ensure we always land on the main Search screen.
        val navHost =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as? NavHostFragment
                ?: return
        val navController = navHost.navController
        window.decorView.post {
            val current = navController.currentDestination?.id ?: return@post
            if (current == R.id.indexFragment) {
                val opts = NavOptions.Builder()
                    .setPopUpTo(R.id.indexFragment, true)
                    .build()
                navController.navigate(R.id.searchFragment, null, opts)
            }
        }
    }
}
