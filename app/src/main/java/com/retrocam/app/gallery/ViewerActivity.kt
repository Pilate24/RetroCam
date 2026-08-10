package com.retrocam.app.gallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.retrocam.app.R
import com.retrocam.app.databinding.ActivityViewerBinding

/** Full-size view of one saved shot, with the system share sheet. */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var photoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intentUri()
        if (uri == null) {
            finish()
            return
        }
        photoUri = uri

        binding.photo.setImageURI(uri)
        binding.photoName.text = intent.getStringExtra(EXTRA_NAME).orEmpty()
        binding.backButton.setOnClickListener { finish() }
        binding.shareButton.setOnClickListener { share(uri) }
    }

    @Suppress("DEPRECATION")
    private fun intentUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_URI)
        }

    /**
     * The chooser is the consent step — the user picks the destination app, and the read grant
     * rides along with the URI rather than being handed out ahead of time.
     */
    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_photo)))
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
    }
}
