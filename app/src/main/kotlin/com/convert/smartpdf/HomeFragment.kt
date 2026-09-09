package com.convert.smartpdf.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.convert.smartpdf.R
import com.convert.smartpdf.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val items = generateHomeItems()
        val adapter = HomeAdapter(items) { item ->
            handleNavigation(item)
        }

        val layoutManager = GridLayoutManager(context, 2)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == HomeAdapter.TYPE_HEADER) 2 else 1
            }
        }

        binding.rvHome.layoutManager = layoutManager
        binding.rvHome.adapter = adapter
    }

    private fun handleNavigation(item: HomeItem.Tool) {
        when(item.id) {
            "PASSPORT_MAKER" -> {
                val intent = Intent(requireContext(), PassportMakerActivity::class.java)
                startActivity(intent)
            }
            "IMG_TO_PDF" -> findNavController().navigate(R.id.action_home_to_imgToPdf)
            "TEXT_TO_PDF" -> findNavController().navigate(R.id.action_home_to_textToPdf)
            "IMG_RESIZER" -> findNavController().navigate(R.id.action_home_to_imageResizer)
            "PDF_TO_TEXT" -> findNavController().navigate(R.id.action_home_to_pdfToText)
            "PDF_TO_JPG" -> findNavController().navigate(R.id.action_home_to_pdfToJpg)
            "SPLIT" -> findNavController().navigate(R.id.action_home_to_splitPdf)
            "COMPRESS" -> findNavController().navigate(R.id.action_home_to_compressPdf)
            "MERGE" -> findNavController().navigate(R.id.action_home_to_mergePdf)
            "ADD_WATERMARK" -> findNavController().navigate(R.id.action_home_to_addWatermark)
            "PROTECT" -> findNavController().navigate(R.id.action_home_to_protectPdf)
            "ROTATE" -> findNavController().navigate(R.id.action_home_to_rotatePdf)
            "ORGANIZE" -> findNavController().navigate(R.id.action_home_to_organizePdf)
            "SIGN" -> findNavController().navigate(R.id.action_home_to_signPdf)
            "RESIZE_PDF" -> findNavController().navigate(R.id.action_home_to_resizePdf)

            "PDF_TO_DOCX" -> {
                val intent = Intent(requireContext(), PdfToDocxActivity::class.java)
                startActivity(intent)
            }
            "PDF_TO_EXCEL" -> {
                val intent = Intent(requireContext(), PdfToExcelActivity::class.java)
                startActivity(intent)
            }
            else -> Toast.makeText(context, "Feature under development", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateHomeItems(): List<HomeItem> {
        val list = mutableListOf<HomeItem>()

        // 1. FAVOURITES
        list.add(HomeItem.Header("FAVOURITES"))
        list.add(HomeItem.Tool("MERGE", "Merge PDF", R.drawable.ic_merge))
        list.add(HomeItem.Tool("IMG_RESIZER", "Image Resizer", R.drawable.ic_image_resizer))
        list.add(HomeItem.Tool("RESIZE_PDF", "Resize PDF", R.drawable.ic_resize_pdf))
        list.add(HomeItem.Tool("ORGANIZE", "Organize PDF", R.drawable.ic_organize))
        list.add(HomeItem.Tool("PROTECT", "Protect PDF", R.drawable.ic_protect))
        list.add(HomeItem.Tool("ADD_WATERMARK", "Add Watermark", R.drawable.ic_watermark))

        // 2. CREATE & CONVERT
        list.add(HomeItem.Header("CREATE & CONVERT"))
        list.add(HomeItem.Tool("PASSPORT_MAKER", "Passport Photo", R.drawable.ic_passport_maker))
        list.add(HomeItem.Tool("IMG_TO_PDF", "Image to PDF", R.drawable.ic_img_to_pdf))
        list.add(HomeItem.Tool("TEXT_TO_PDF", "Text to PDF", R.drawable.ic_text_to_pdf))
        list.add(HomeItem.Tool("PDF_TO_JPG", "PDF to JPG", R.drawable.ic_pdf_to_jpg))
        list.add(HomeItem.Tool("PDF_TO_TEXT", "PDF to Text", R.drawable.ic_pdf_to_text))
        list.add(HomeItem.Tool("PDF_TO_DOCX", "PDF to DOCX", R.drawable.ic_pdf_to_docx))
        list.add(HomeItem.Tool("PDF_TO_EXCEL", "PDF to Excel", R.drawable.ic_pdf_to_excel))

        // 3. ORGANIZE PDF
        list.add(HomeItem.Header("ORGANIZE PDF"))
        list.add(HomeItem.Tool("SPLIT", "Split PDF", R.drawable.ic_split_pdf))
        list.add(HomeItem.Tool("ROTATE", "Rotate PDF", R.drawable.ic_rotate_pdf))

        // 4. OPTIMIZE & CONTENT
        list.add(HomeItem.Header("OPTIMIZE & CONTENT"))
        list.add(HomeItem.Tool("COMPRESS", "Compress PDF", R.drawable.ic_compress_pdf))
        list.add(HomeItem.Tool("SIGN", "Sign PDF", R.drawable.ic_sign_pdf))

        return list
    }

    sealed class HomeItem {
        data class Header(val title: String) : HomeItem()
        data class Tool(val id: String, val title: String, val iconRes: Int) : HomeItem()
    }

    class HomeAdapter(private val items: List<HomeItem>, private val onClick: (HomeItem.Tool) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        companion object {
            const val TYPE_HEADER = 0
            const val TYPE_TOOL = 1
        }
        override fun getItemViewType(position: Int): Int = if (items[position] is HomeItem.Header) TYPE_HEADER else TYPE_TOOL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            } else {
                ToolViewHolder(inflater.inflate(R.layout.item_tool_card, parent, false))
            }
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HomeItem.Header -> (holder as HeaderViewHolder).bind(item)
                is HomeItem.Tool -> (holder as ToolViewHolder).bind(item, onClick)
            }
        }
        override fun getItemCount() = items.size

        class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(item: HomeItem.Header) {
                itemView.findViewById<TextView>(R.id.tvSectionTitle).text = item.title
            }
        }
        class ToolViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(item: HomeItem.Tool, onClick: (HomeItem.Tool) -> Unit) {
                itemView.findViewById<TextView>(R.id.tvTitle).text = item.title
                val iconView = itemView.findViewById<ImageView>(R.id.ivIcon)
                if (iconView != null) {
                    iconView.setImageResource(item.iconRes)
                }
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
}
