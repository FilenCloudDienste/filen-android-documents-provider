package io.filen.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class TestActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// write to the file to simulate a user being logged in

		Log.d("TestActivity", "Auth file written with test data to ${filesDir.absolutePath}/auth.json")

		// Simple button to open the system file picker, which will show your provider
		val button = Button(this).apply {
			text = "Test Documents Provider"
			setOnClickListener { openDocumentsPicker() }
		}

		val switch = Switch(this).apply {
			text = "switch state"
			setOnCheckedChangeListener{_, isChecked ->
				if (isChecked) {
					writeAuthFile()
				} else {
					clearAuthFile()
				}
			}
		}
		setContentView(switch)
	}

	private fun openDocumentsPicker() {

		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "*/*" // or specific MIME types your provider handles
		}
		startActivityForResult(intent, 1)
	}

	private fun writeAuthFile() {
		val authFile = openFileOutput("auth.json", MODE_PRIVATE)

		val content = """
		{
			"providerEnabled": true,
			"sdkConfig": {
				"email": "${BuildConfig.EMAIL}",
				"password": "redacted",
				"twoFactorCode": "",
				"masterKeys": ${BuildConfig.MASTER_KEYS},
				"apiKey": "${BuildConfig.API_KEY}",
				"publicKey": "",
				"privateKey": "${BuildConfig.PRIVATE_KEY}",
				"authVersion": ${BuildConfig.AUTH_VERSION},
				"baseFolderUUID": "${BuildConfig.BASE_FOLDER_UUID}",
				"userId": 0,
				"metadataCache": false,
				"tmpPath": "",
				"connectToSocket": false
			}
		}
		""".trimIndent()
		authFile.write(content.toByteArray())
	}

	private fun clearAuthFile() {
		val authFile = openFileOutput("auth.json", MODE_PRIVATE)

		val content = """
		{
			"providerEnabled": false,
			"sdkConfig": null
		}
		""".trimIndent()
		authFile.write(content.toByteArray())
	}
}
