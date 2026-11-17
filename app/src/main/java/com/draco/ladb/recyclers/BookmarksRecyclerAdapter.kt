package com.draco.ladb.recyclers

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.draco.ladb.R

class BookmarksRecyclerAdapter(context: Context) : RecyclerView.Adapter<BookmarksRecyclerAdapter.ViewHolder>() {
    private val list = mutableListOf<String>()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var pickHook: (String) -> Unit = {}
    var deleteHook: (String) -> Unit = {}
    var editHook: (String) -> Unit = {}

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: TextView = view.findViewById(R.id.content)
        val delete: ImageButton = view.findViewById(R.id.delete)
        val edit: ImageButton = view.findViewById(R.id.edit)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateList(refresh: Boolean = true) {
        list.clear()
        list.addAll((prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()).sorted())
        if (refresh) notifyDataSetChanged()
    }

    private fun saveList() {
        with(prefs.edit()) {
            putStringSet("bookmarks", list.toSet())
            apply()
        }
    }

    fun add(text: String) {
        if (text.isBlank() || text in list) return
        val index = list.binarySearch(text).let { if (it < 0) -it - 1 else it }
        list.add(index, text)
        notifyItemInserted(index)
        saveList()
    }

    fun delete(text: String) {
        val index = list.indexOf(text)
        if (index < 0) return
        list.removeAt(index)
        notifyItemRemoved(index)
        saveList()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun edit(text: String, newText: String) {
        if (newText.isBlank()) return
        val oldIndex = list.indexOf(text)
        if (oldIndex < 0) return
        if (newText != text && newText in list) return
        list.removeAt(oldIndex)
        val newIndex = list.binarySearch(newText).let { if (it < 0) -it - 1 else it }
        list.add(newIndex, newText)
        notifyDataSetChanged()
        saveList()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.recycler_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = list[position]
        holder.content.text = text
        holder.itemView.setOnClickListener { pickHook(text) }
        holder.delete.setOnClickListener { deleteHook(text) }
        holder.edit.setOnClickListener { editHook(text) }
    }

    override fun getItemCount() = list.size
}