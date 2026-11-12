package com.example.attendanceapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val CAMPUS_LAT = 18.1891801
    private val CAMPUS_LON = 83.3925303
    private val FENCE_RADIUS_METERS = 100.0f
    private val LOCATION_PERMISSION_CODE = 101

    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var statusCard: CardView
    private lateinit var tvFenceStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvCurrentAddress: TextView
    private lateinit var btnMarkAttendance: Button
    private lateinit var btnRefreshLocation: Button
    private lateinit var rvHistory: RecyclerView

    private val historyList = ArrayList<AttendanceRecord>()
    private lateinit var adapter: AttendanceAdapter

    private var isInsideFence = false
    private var currentDistanceMeters = 0.0f
    private var fetchedAddress = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()

        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val tvStudentEmail = findViewById<TextView>(R.id.tvStudentEmail)
        val btnLogout = findViewById<Button>(R.id.btnLogout)
        statusCard = findViewById(R.id.statusCard)
        tvFenceStatus = findViewById(R.id.tvFenceStatus)
        tvDistance = findViewById(R.id.tvDistance)
        tvCurrentAddress = findViewById(R.id.tvCurrentAddress)
        btnMarkAttendance = findViewById(R.id.btnMarkAttendance)
        btnRefreshLocation = findViewById(R.id.btnRefreshLocation)
        rvHistory = findViewById(R.id.rvHistory)

        tvStudentEmail.text = auth.currentUser?.email ?: "Student"

        updateStatusCardColor(false)

        adapter = AttendanceAdapter(historyList)
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter

        loadAttendanceHistory()

        btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        btnRefreshLocation.setOnClickListener {
            checkLocationPermissionAndFetch()
        }

        btnMarkAttendance.setOnClickListener {
            markAttendance()
        }

        checkLocationPermissionAndFetch()
    }

    private fun getThemeColor(attrResId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrResId, typedValue, true)
        return typedValue.data
    }

    private fun updateStatusCardColor(inside: Boolean) {
        if (inside) {
            val greenColor = getThemeColor(R.attr.statusInsideColor)
            statusCard.setCardBackgroundColor(greenColor)
        } else {
            val redColor = getThemeColor(R.attr.statusOutsideColor)
            statusCard.setCardBackgroundColor(redColor)
        }
    }

    private fun checkLocationPermissionAndFetch() {
        val fineGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            fetchCurrentLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchCurrentLocation()
            } else {
                isInsideFence = false
                updateStatusCardColor(false)
                tvFenceStatus.text = "Location Permission Denied"
                tvDistance.text = "Please grant location permission to verify campus fence."
                tvCurrentAddress.text = "Address unavailable without permission"
                btnMarkAttendance.text = "Mark Attendance (Outside - Permission Denied)"
                Toast.makeText(
                    this,
                    "Location permission is required to mark attendance inside the campus.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun fetchCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        tvFenceStatus.text = "Getting GPS Coordinates..."
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        CAMPUS_LAT,
                        CAMPUS_LON,
                        location.latitude,
                        location.longitude,
                        results
                    )

                    currentDistanceMeters = results[0]
                    isInsideFence = currentDistanceMeters <= FENCE_RADIUS_METERS

                    val formattedDist = String.format(Locale.US, "%.1f", currentDistanceMeters)
                    val statusWord = if (isInsideFence) "Inside" else "Outside"

                    updateStatusCardColor(isInsideFence)

                    tvFenceStatus.text = "Status: $statusWord Campus Fence"
                    tvDistance.text = "Distance: $formattedDist m (Max allowed: 100 m)"
                    btnMarkAttendance.text = "Mark Attendance ($statusWord - $formattedDist m)"

                    fetchAddressFromApi(location.latitude, location.longitude)
                } else {
                    tvFenceStatus.text = "Unable to get GPS fix. Turn on Location."
                }
            }
    }

    private fun fetchAddressFromApi(lat: Double, lon: Double) {
        tvCurrentAddress.text = "Fetching address from API..."
        Thread {
            try {
                val urlString =
                    "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "AttendanceApp/1.0")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String? = reader.readLine()
                while (line != null) {
                    response.append(line)
                    line = reader.readLine()
                }
                reader.close()

                val json = JSONObject(response.toString())
                val address = json.optString("display_name", "Campus Location ($lat, $lon)")

                runOnUiThread {
                    fetchedAddress = address
                    tvCurrentAddress.text = "Address: $fetchedAddress"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    fetchedAddress = "Lat: $lat, Lon: $lon"
                    tvCurrentAddress.text = "Address: $fetchedAddress"
                }
            }
        }.start()
    }

    private fun markAttendance() {
        if (!isInsideFence) {
            Toast.makeText(
                this,
                "Cannot mark attendance! You are outside the 100 m campus radius.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (fetchedAddress.isEmpty()) {
            Toast.makeText(this, "Please wait for address to load...", Toast.LENGTH_SHORT).show()
            return
        }

        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        for (record in historyList) {
            if (record.date == todayDate) {
                Toast.makeText(
                    this,
                    "Attendance already marked for today ($todayDate)!",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val formattedDist = String.format(Locale.US, "%.1f", currentDistanceMeters)
        val newRecord = AttendanceRecord(todayDate, currentTime, formattedDist, fetchedAddress)

        historyList.add(0, newRecord)
        adapter.notifyDataSetChanged()
        saveAttendanceHistory()

        Toast.makeText(this, "Attendance Marked Successfully!", Toast.LENGTH_SHORT).show()
    }

    private fun getPrefsKey(): String {
        val uid = auth.currentUser?.uid ?: "default_user"
        return "history_$uid"
    }

    private fun saveAttendanceHistory() {
        val sharedPrefs = getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()

        for (record in historyList) {
            val obj = JSONObject()
            obj.put("date", record.date)
            obj.put("time", record.time)
            obj.put("distance", record.distanceMeters)
            obj.put("address", record.address)
            jsonArray.put(obj)
        }

        sharedPrefs.edit().putString(getPrefsKey(), jsonArray.toString()).apply()
    }

    private fun loadAttendanceHistory() {
        historyList.clear()
        val sharedPrefs = getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)
        val jsonString = sharedPrefs.getString(getPrefsKey(), null) ?: return

        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val record = AttendanceRecord(
                    obj.getString("date"),
                    obj.getString("time"),
                    obj.getString("distance"),
                    obj.getString("address")
                )
                historyList.add(record)
            }
            adapter.notifyDataSetChanged()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}