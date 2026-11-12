package com.example.attendanceapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AttendanceRecord(
    val date: String,
    val time: String,
    val distanceMeters: String,
    val address: String
)

class AttendanceAdapter(private val records: ArrayList<AttendanceRecord>) :
    RecyclerView.Adapter<AttendanceAdapter.AttendanceViewHolder>() {

    class AttendanceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDateAndTime: TextView = itemView.findViewById(R.id.tvDateAndTime)
        val tvDistanceItem: TextView = itemView.findViewById(R.id.tvDistanceItem)
        val tvAddressItem: TextView = itemView.findViewById(R.id.tvAddressItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttendanceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_attendance, parent, false)
        return AttendanceViewHolder(view)
    }

    override fun onBindViewHolder(holder: AttendanceViewHolder, position: Int) {
        val item = records[position]
        holder.tvDateAndTime.text = "${item.date}  •  ${item.time}"
        holder.tvDistanceItem.text = "Distance from centre: ${item.distanceMeters} m"
        holder.tvAddressItem.text = "Address: ${item.address}"
    }

    override fun getItemCount(): Int {
        return records.size
    }
}