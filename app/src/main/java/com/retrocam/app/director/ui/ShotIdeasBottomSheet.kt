package com.retrocam.app.director.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.retrocam.app.R
import com.retrocam.app.databinding.LayoutShotIdeasSheetBinding
import com.retrocam.app.director.model.ShotIdea

/**
 * Bottom sheet dialog presenting the 3 alternative ways to photograph the current scene.
 */
class ShotIdeasBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutShotIdeasSheetBinding? = null
    private val binding get() = _binding!!

    var ideas: List<ShotIdea> = emptyList()
    var onIdeaSelected: ((ShotIdea) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutShotIdeasSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ideasSheetCloseButton.setOnClickListener {
            dismiss()
        }

        renderIdeas()
    }

    private fun renderIdeas() {
        val container = binding.ideasContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        ideas.forEachIndexed { index, idea ->
            val itemView = inflater.inflate(R.layout.item_shot_idea, container, false)

            itemView.findViewById<TextView>(R.id.ideaNumber).text = "${index + 1}"
            itemView.findViewById<TextView>(R.id.ideaName).text = idea.name
            itemView.findViewById<TextView>(R.id.ideaVisualEffect).text = idea.visualEffect
            itemView.findViewById<TextView>(R.id.ideaLensChip).text = "LENS: ${idea.lens.getLabel()}"
            itemView.findViewById<TextView>(R.id.ideaZoomChip).text = "ZOOM: ${idea.zoomRatio?.let { String.format("%.1f×", it) } ?: "1.0×"}"
            itemView.findViewById<TextView>(R.id.ideaAspectChip).text = "ASPECT: ${idea.aspectOption.displayLabel}"
            itemView.findViewById<TextView>(R.id.ideaPosition).text = "📍 ${idea.cameraPosition}"
            itemView.findViewById<TextView>(R.id.ideaTip).text = "💡 ${idea.compositionTip}"
            itemView.findViewById<TextView>(R.id.ideaExplanation).text = "🔍 ${idea.explanation}"

            itemView.findViewById<MaterialButton>(R.id.ideaApplyButton).setOnClickListener {
                onIdeaSelected?.invoke(idea)
                dismiss()
            }

            container.addView(itemView)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ShotIdeasBottomSheet"

        fun newInstance(ideas: List<ShotIdea>): ShotIdeasBottomSheet {
            return ShotIdeasBottomSheet().apply {
                this.ideas = ideas
            }
        }
    }
}
