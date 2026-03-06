package com.sleepy.petcats

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startGame()
        } else {
            showPermissionDeniedAndExit()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btn_start)
        btnStart.setOnClickListener {
            checkPermissionAndStart()
        }
    }

    private fun checkPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                AlertDialog.Builder(this)
                    .setTitle("Notifications Required")
                    .setMessage(
                        "Pet Cats requires notifications to inform you about the game and upcoming events.\n\n" +
                        "This permission is mandatory to play the game."
                    )
                    .setCancelable(false)
                    .setPositiveButton("I Understand") { _, _ ->
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .show()
                return
            }
        }
        startGame()
    }

    private fun startGame() {
        Toast.makeText(this, "Welcome to Pet Cats!", Toast.LENGTH_SHORT).show()
        // Here you would navigate to the actual game screen
    }

    private fun showPermissionDeniedAndExit() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("Notifications are mandatory to play Pet Cats. The app will now close.")
            .setCancelable(false)
            .setPositiveButton("Close") { _, _ ->
                finish()
            }
            .show()
    }
}
