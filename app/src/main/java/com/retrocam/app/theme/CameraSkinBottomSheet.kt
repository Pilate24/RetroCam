package com.retrocam.app.theme

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.retrocam.app.databinding.DialogCameraSkinBinding

/**
 * Skeuomorphic BottomSheet allowing the user to select one of 4 Retro Camera Body themes.
 */
class CameraSkinBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogCameraSkinBinding? = null
    private val binding get() = _binding!!

    var onSkinSelected: ((CameraSkin) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogCameraSkinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentSkin = CameraSkinManager.getSavedSkin(requireContext())
        highlightActiveSkin(currentSkin)

        binding.cardClassic.setOnClickListener {
            selectSkin(CameraSkin.CLASSIC_1970S)
        }

        binding.cardNeon.setOnClickListener {
            selectSkin(CameraSkin.TRANSLUCENT_NEON_90S)
        }

        binding.cardCardboard.setOnClickListener {
            selectSkin(CameraSkin.DISPOSABLE_CARDBOARD)
        }

        binding.cardBrass.setOnClickListener {
            selectSkin(CameraSkin.GOLDEN_BRASS)
        }
    }

    private fun highlightActiveSkin(skin: CameraSkin) {
        binding.badgeClassicActive.visibility =
            if (skin == CameraSkin.CLASSIC_1970S) View.VISIBLE else View.GONE
        binding.badgeNeonActive.visibility =
            if (skin == CameraSkin.TRANSLUCENT_NEON_90S) View.VISIBLE else View.GONE
        binding.badgeCardboardActive.visibility =
            if (skin == CameraSkin.DISPOSABLE_CARDBOARD) View.VISIBLE else View.GONE
        binding.badgeBrassActive.visibility =
            if (skin == CameraSkin.GOLDEN_BRASS) View.VISIBLE else View.GONE
    }

    private fun selectSkin(skin: CameraSkin) {
        val context = requireContext()
        CameraSkinManager.saveSkin(context, skin)
        highlightActiveSkin(skin)
        onSkinSelected?.invoke(skin)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CameraSkinBottomSheet"

        fun newInstance(onSkinSelected: (CameraSkin) -> Unit): CameraSkinBottomSheet {
            return CameraSkinBottomSheet().apply {
                this.onSkinSelected = onSkinSelected
            }
        }
    }
}
