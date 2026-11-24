package com.mycar.launcher

import android.content.pm.ResolveInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mycar.launcher.databinding.ItemAppIconBinding

class AppGridAdapter(
    private val apps: List<ResolveInfo>,
    private val isNightMode: Boolean,
    private val onAppClick: (ResolveInfo) -> Unit
) : RecyclerView.Adapter<AppGridAdapter.AppViewHolder>() {

    inner class AppViewHolder(val binding: ItemAppIconBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppIconBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val info = apps[position]
        val pm = holder.itemView.context.packageManager
        val label = info.loadLabel(pm).toString()
        val icon = info.loadIcon(pm)

        holder.binding.ivAppIcon.setImageDrawable(icon)
        holder.binding.tvAppName.text = label
        holder.binding.tvAppName.setTextColor(
            holder.itemView.context.getColor(if (isNightMode) R.color.night_text else R.color.day_text)
        )
        holder.itemView.setOnClickListener { onAppClick(info) }
    }

    override fun getItemCount(): Int = apps.size
}
