package io.filen.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import java.nio.file.Paths

class TestActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// write to the file to simulate a user being logged in

		val authFile = Paths.get(filesDir.absolutePath,"auth.json")

		Log.d("TestActivity", "Auth file written with test data to $authFile")

		// Simple button to open the system file picker, which will show your provider
		val button = Button(this).apply {
			text = "Test Documents Provider"
			setOnClickListener { openDocumentsPicker() }
		}

		val switch = Switch(this).apply {
			text = "switch state"
			setOnCheckedChangeListener{_, isChecked ->
				if (isChecked) {
					TestAuth.provision(filesDir.absolutePath, authFile)
				} else {
					TestAuth.writeDisabled(filesDir.absolutePath, authFile)
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

}
