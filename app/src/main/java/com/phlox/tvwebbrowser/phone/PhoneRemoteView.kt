package com.phlox.tvwebbrowser.phone

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewPhoneRemoteBinding
import com.phlox.tvwebbrowser.sync.QrBitmap
import com.phlox.tvwebbrowser.utils.themeColor

/**
 * The TV screen of the phone remote. It only draws the QR/link and tells [Callback] when the user
 * closes it; [PhoneRemoteServer] owns the socket.
 */
class PhoneRemoteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onStop()
        fun onClosed()
    }

    private val vb = ViewPhoneRemoteBinding.inflate(LayoutInflater.from(context), this)
    private var callback: Callback? = null
    private var shownUrl: String? = null

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }

    val isOpen: Boolean get() = isVisible

    init {
        orientation = VERTICAL
        visibility = GONE
        isClickable = true
        setBackgroundColor(context.themeColor(R.attr.tbColorDsSurface))
        vb.ibBack.setOnClickListener { close() }
        vb.btnStop.setOnClickListener {
            callback?.onStop()
            close()
        }
    }

    fun attach(callback: Callback) {
        this.callback = callback
    }

    fun show(url: String?) {
        visibility = VISIBLE
        backCallback.isEnabled = true
        shownUrl = url
        vb.pbQr.isVisible = url == null
        vb.ivQr.setImageBitmap(url?.let { QrBitmap.of(it, 6) })
        (vb.ivQr.drawable as? android.graphics.drawable.BitmapDrawable)?.setFilterBitmap(false)
        vb.tvUrl.text = url ?: context.getString(R.string.phone_remote_starting)
        val host = url?.let { Uri.parse(it).host }
        vb.tvNetwork.text = if (host == null) {
            context.getString(R.string.phone_remote_no_network)
        } else {
            context.getString(R.string.sync_same_network, host)
        }
        vb.btnStop.requestFocus()
    }

    /** Hide the QR but keep the server and the phone session alive (the page is opened) */
    fun hideViewOnly() {
        if (!isOpen) return
        visibility = GONE
        backCallback.isEnabled = false
    }

    private fun close() {
        callback?.onStop()
        if (isOpen) {
            visibility = GONE
            backCallback.isEnabled = false
            callback?.onClosed()
        }
    }
}
