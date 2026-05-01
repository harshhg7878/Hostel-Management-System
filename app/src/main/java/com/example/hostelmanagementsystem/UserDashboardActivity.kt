package com.example.hostelmanagementsystem

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.google.gson.Gson
import com.example.hostelmanagementsystem.adapters.NotificationAdapter
import com.example.hostelmanagementsystem.adapters.UserComplaintAdapter
import com.example.hostelmanagementsystem.adapters.UserRoomAdapter
import com.example.hostelmanagementsystem.adapters.MessPlanAdapter
import com.example.hostelmanagementsystem.models.BookingResponse
import com.example.hostelmanagementsystem.models.ComplaintRequest
import com.example.hostelmanagementsystem.models.ComplaintResponse
import com.example.hostelmanagementsystem.models.CreatePaymentOrderRequest
import com.example.hostelmanagementsystem.models.CreatePaymentOrderResponse
import com.example.hostelmanagementsystem.models.Fee
import com.example.hostelmanagementsystem.models.PaymentVerificationResponse
import com.example.hostelmanagementsystem.models.Room
import com.example.hostelmanagementsystem.models.RoomNotification
import com.example.hostelmanagementsystem.models.RoomRequestItem
import com.example.hostelmanagementsystem.models.RoomRequestResponse
import com.example.hostelmanagementsystem.models.RequestRoomBookingRequest
import com.example.hostelmanagementsystem.models.UserDashboardResponse
import com.example.hostelmanagementsystem.models.VerifyPaymentRequest
import com.example.hostelmanagementsystem.network.ApiClient
import com.example.hostelmanagementsystem.network.NotificationHelper
import com.example.hostelmanagementsystem.network.SessionManager
import com.example.hostelmanagementsystem.network.SocketManager
import com.example.hostelmanagementsystem.ui.UiEffects
import io.socket.client.Socket
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

class UserDashboardActivity : AppCompatActivity(), PaymentResultWithDataListener {
    companion object {
        private const val TAG = "UserDashboardActivity"
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var socket: Socket

    private lateinit var welcomeText: TextView
    private lateinit var emailText: TextView
    private lateinit var currentRoomText: TextView
    private lateinit var currentBookingMeta: TextView
    private lateinit var statAvailableRooms: TextView
    private lateinit var statOccupiedBeds: TextView
    private lateinit var statCapacity: TextView
    private lateinit var releaseRoomBtn: Button
    private lateinit var payFeeBtn: Button
    private lateinit var logoutBtn: TextView
    private lateinit var feeTitleText: TextView
    private lateinit var feeMetaText: TextView
    private lateinit var feeStatusText: TextView
    private lateinit var roomsRecyclerView: RecyclerView
    private lateinit var notificationsRecyclerView: RecyclerView
    private lateinit var complaintsRecyclerView: RecyclerView
    private lateinit var messPlanRecyclerView: RecyclerView
    private lateinit var loadingText: TextView
    private lateinit var emptyText: TextView
    private lateinit var alertsEmptyText: TextView
    private lateinit var complaintsEmptyText: TextView
    private lateinit var messPlanTitleText: TextView
    private lateinit var messPlanEmptyText: TextView
    private lateinit var complaintSubjectInput: EditText
    private lateinit var complaintDescriptionInput: EditText
    private lateinit var submitComplaintBtn: Button

    private var currentBookedRoomId: String? = null
    private var pendingRequestedRoomId: String? = null
    private var selectedRoomId: String? = null
    private var hasActiveBooking: Boolean = false
    private var hasPendingRequest: Boolean = false
    private var currentFee: Fee? = null
    private var payingFeeId: String? = null
    private var shownNotificationIds = mutableSetOf<String>()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_dashboard)
        UiEffects.animateScreenIn(this)

        sessionManager = SessionManager(this)
        socket = SocketManager.getSocket()

        welcomeText = findViewById(R.id.welcomeText)
        emailText = findViewById(R.id.emailText)
        currentRoomText = findViewById(R.id.currentRoomText)
        currentBookingMeta = findViewById(R.id.currentBookingMeta)
        statAvailableRooms = findViewById(R.id.statAvailableRooms)
        statOccupiedBeds = findViewById(R.id.statOccupiedBeds)
        statCapacity = findViewById(R.id.statCapacity)
        releaseRoomBtn = findViewById(R.id.releaseRoomBtn)
        payFeeBtn = findViewById(R.id.payFeeBtn)
        logoutBtn = findViewById(R.id.logoutText)
        feeTitleText = findViewById(R.id.feeTitleText)
        feeMetaText = findViewById(R.id.feeMetaText)
        feeStatusText = findViewById(R.id.feeStatusText)
        roomsRecyclerView = findViewById(R.id.roomsRecyclerView)
        notificationsRecyclerView = findViewById(R.id.notificationsRecyclerView)
        complaintsRecyclerView = findViewById(R.id.complaintsRecyclerView)
        messPlanRecyclerView = findViewById(R.id.messPlanRecyclerView)
        loadingText = findViewById(R.id.loadingText)
        emptyText = findViewById(R.id.emptyText)
        alertsEmptyText = findViewById(R.id.alertsEmptyText)
        complaintsEmptyText = findViewById(R.id.complaintsEmptyText)
        messPlanTitleText = findViewById(R.id.messPlanTitleText)
        messPlanEmptyText = findViewById(R.id.messPlanEmptyText)
        complaintSubjectInput = findViewById(R.id.complaintSubjectInput)
        complaintDescriptionInput = findViewById(R.id.complaintDescriptionInput)
        submitComplaintBtn = findViewById(R.id.submitComplaintBtn)

        if (sessionManager.getUserId().isBlank()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        roomsRecyclerView.layoutManager = LinearLayoutManager(this)
        roomsRecyclerView.isNestedScrollingEnabled = false
        notificationsRecyclerView.layoutManager = LinearLayoutManager(this)
        notificationsRecyclerView.isNestedScrollingEnabled = false
        complaintsRecyclerView.layoutManager = LinearLayoutManager(this)
        complaintsRecyclerView.isNestedScrollingEnabled = false
        messPlanRecyclerView.layoutManager = LinearLayoutManager(this)
        messPlanRecyclerView.isNestedScrollingEnabled = false

        welcomeText.text = "Welcome, ${sessionManager.getUserName()}"
        emailText.text = sessionManager.getUserEmail()
        Checkout.preload(applicationContext)

        requestNotificationPermissionIfNeeded()

        logoutBtn.setOnClickListener {
            sessionManager.clearSession()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        submitComplaintBtn.setOnClickListener {
            submitComplaint()
        }

        payFeeBtn.setOnClickListener {
            startFeePayment()
        }
    }

    override fun onStart() {
        super.onStart()
        bindRealtimeEvents()
        if (!socket.connected()) {
            socket.connect()
        }
        fetchDashboard()
    }

    override fun onStop() {
        super.onStop()
        socket.off("roomsUpdated")
        socket.off("bookingsUpdated")
        socket.off("userNotificationsUpdated")
        socket.off("userComplaintsUpdated")
        socket.off("noticesUpdated")
        socket.off("messPlansUpdated")
        socket.off("feesUpdated")
        socket.disconnect()
    }

    private fun bindRealtimeEvents() {
        socket.off("roomsUpdated")
        socket.off("bookingsUpdated")
        socket.off("userNotificationsUpdated")
        socket.off("userComplaintsUpdated")
        socket.off("noticesUpdated")
        socket.off("messPlansUpdated")
        socket.off("feesUpdated")

        socket.on("roomsUpdated") {
            runOnUiThread { fetchDashboard(silent = true) }
        }

        socket.on("bookingsUpdated") {
            runOnUiThread { fetchDashboard(silent = true) }
        }

        socket.on("userNotificationsUpdated") {
            runOnUiThread { fetchDashboard(silent = true) }
        }

        socket.on("userComplaintsUpdated") {
            runOnUiThread { fetchDashboard(silent = true) }
        }

        socket.on("noticesUpdated") {
            runOnUiThread { fetchDashboard(silent = true) }
        }

        socket.on("messPlansUpdated") {
            runOnUiThread { fetchDashboard(silent = true) }
        }

        socket.on("feesUpdated") {
            runOnUiThread { fetchDashboard(silent = true) }
        }
    }

    private fun fetchDashboard(silent: Boolean = false) {
        if (!silent) {
            loadingText.text = "Loading dashboard..."
        }

        ApiClient.apiService.getUserDashboard(sessionManager.getUserId())
            .enqueue(object : Callback<UserDashboardResponse> {
                override fun onResponse(
                    call: Call<UserDashboardResponse>,
                    response: Response<UserDashboardResponse>
                ) {
                    loadingText.text = ""

                    if (response.isSuccessful && response.body() != null) {
                        bindDashboard(response.body()!!)
                    } else {
                        Toast.makeText(
                            this@UserDashboardActivity,
                            "Failed to load user dashboard",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<UserDashboardResponse>, t: Throwable) {
                    loadingText.text = ""
                    Toast.makeText(
                        this@UserDashboardActivity,
                        "Error: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun bindDashboard(data: UserDashboardResponse) {
        statAvailableRooms.text = "${data.stats.availableRooms}\nAvailable"
        statOccupiedBeds.text = "${data.stats.occupiedBeds}\nOccupied"
        statCapacity.text = "${data.stats.totalCapacity}\nCapacity"

        currentBookedRoomId = data.currentBooking?.room?._id
        pendingRequestedRoomId = data.pendingRequest?.preferredRoom?._id
        hasActiveBooking = data.currentBooking != null
        hasPendingRequest = data.pendingRequest != null
        currentFee = data.currentFee
        val availableRooms = data.availableRooms

        bindFeeState(data.currentFee)

        if (hasActiveBooking || hasPendingRequest) {
            selectedRoomId = null
        } else if (selectedRoomId != null && availableRooms.none { it._id == selectedRoomId }) {
            selectedRoomId = null
        }

        when {
            data.currentBooking?.room != null -> {
                val room = data.currentBooking.room
                currentRoomText.text = "Assigned room: ${room.roomNumber}"
                currentBookingMeta.text = "Floor ${room.floor} | ${room.occupiedBeds}/${room.capacity} occupied"
                releaseRoomBtn.isEnabled = true
                releaseRoomBtn.text = "Release Current Room"
                releaseRoomBtn.setOnClickListener { releaseRoom() }
            }

            data.pendingRequest != null -> {
                bindPendingRequest(data.pendingRequest)
            }

            else -> {
                bindRoomSelectionState(availableRooms)
            }
        }

        val visibleRooms = buildVisibleRooms(
            availableRooms = availableRooms,
            currentRoom = data.currentBooking?.room,
            pendingRoom = data.pendingRequest?.preferredRoom
        )

        emptyText.text = if (visibleRooms.isEmpty()) "No rooms available right now" else ""
        alertsEmptyText.text = if (data.notifications.isEmpty()) "No alerts yet" else ""

        renderRooms(visibleRooms, availableRooms)

        notificationsRecyclerView.adapter = NotificationAdapter(data.notifications)
        showUnreadNotifications(data.notifications)
        complaintsEmptyText.text = if (data.complaints.isEmpty()) "No complaints raised yet" else ""
        complaintsRecyclerView.adapter = UserComplaintAdapter(data.complaints)
        bindMessPlans(data)
    }

    private fun bindMessPlans(data: UserDashboardResponse) {
        val history = data.messPlanHistory
        messPlanTitleText.text = if (data.todaysMessPlan != null) {
            "Today's Mess Plan"
        } else {
            "Mess Plan History"
        }
        messPlanEmptyText.text = if (history.isEmpty()) "No mess plans published yet" else ""
        messPlanRecyclerView.adapter = MessPlanAdapter(history)
    }

    private fun bindFeeState(fee: Fee?) {
        currentFee = fee

        when {
            fee == null -> {
                feeTitleText.text = "No fee assigned yet"
                feeMetaText.text = "Your hostel fee status will appear here once an admin creates a fee."
                feeStatusText.text = ""
                payFeeBtn.isEnabled = false
                payFeeBtn.text = "No Fee Available"
            }

            fee.status.equals("paid", ignoreCase = true) -> {
                feeTitleText.text = "${fee.title} (${fee.monthLabel})"
                feeMetaText.text = "Amount: ${formatCurrency(fee.amount)} ${fee.currency}. This fee has already been paid."
                feeStatusText.text = "Status: Paid"
                payFeeBtn.isEnabled = false
                payFeeBtn.text = "Already Paid"
            }

            else -> {
                feeTitleText.text = "${fee.title} (${fee.monthLabel})"
                feeMetaText.text = "Amount: ${formatCurrency(fee.amount)} ${fee.currency}. Complete the payment to submit your hostel fee."
                feeStatusText.text = "Status: ${fee.status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}"
                payFeeBtn.isEnabled = true
                payFeeBtn.text = "Pay ${formatCurrency(fee.amount)}"
            }
        }
    }

    private fun startFeePayment() {
        val fee = currentFee

        if (fee == null) {
            Toast.makeText(this, "No fee available for payment", Toast.LENGTH_SHORT).show()
            return
        }

        if (fee.status.equals("paid", ignoreCase = true)) {
            Toast.makeText(this, "This fee is already paid", Toast.LENGTH_SHORT).show()
            return
        }

        payFeeBtn.isEnabled = false
        payFeeBtn.text = "Preparing Payment..."

        ApiClient.apiService.createPaymentOrder(
            CreatePaymentOrderRequest(
                userId = sessionManager.getUserId(),
                feeId = fee.id
            )
        ).enqueue(object : Callback<CreatePaymentOrderResponse> {
            override fun onResponse(
                call: Call<CreatePaymentOrderResponse>,
                response: Response<CreatePaymentOrderResponse>
            ) {
                if (response.isSuccessful && response.body() != null) {
                    val orderResponse = response.body()!!
                    payingFeeId = orderResponse.fee.id
                    openRazorpayCheckout(orderResponse)
                } else {
                    payFeeBtn.isEnabled = true
                    payFeeBtn.text = "Pay ${formatCurrency(fee.amount)}"
                    Toast.makeText(
                        this@UserDashboardActivity,
                        extractErrorMessage(response, "Unable to start payment"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<CreatePaymentOrderResponse>, t: Throwable) {
                payFeeBtn.isEnabled = true
                payFeeBtn.text = "Pay ${formatCurrency(fee.amount)}"
                Toast.makeText(
                    this@UserDashboardActivity,
                    "Error: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun openRazorpayCheckout(order: CreatePaymentOrderResponse) {
        try {
            val checkout = Checkout()
            checkout.setKeyID(order.keyId)
            checkout.setFullScreenDisable(true)

            val options = JSONObject().apply {
                put("key", order.keyId)
                put("name", "Hostel Management")
                put("description", order.fee.title)
                put("order_id", order.orderId)
                put("currency", order.currency)
                put("amount", order.amount)
                put("prefill.email", order.user.email)
                put("theme.color", "#2563EB")
                put(
                    "retry",
                    JSONObject().apply {
                        put("enabled", true)
                        put("max_count", 4)
                    }
                )
            }

            checkout.open(this@UserDashboardActivity, options)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to open Razorpay Checkout", error)
            restoreFeeButtonState()
            Toast.makeText(
                this,
                error.message ?: "Unable to open payment screen",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun renderRooms(visibleRooms: List<Room>, availableRooms: List<Room>) {
        roomsRecyclerView.adapter = UserRoomAdapter(
            rooms = visibleRooms,
            currentBookedRoomId = currentBookedRoomId,
            pendingRequestedRoomId = pendingRequestedRoomId,
            selectedRoomId = selectedRoomId,
            hasActiveBooking = hasActiveBooking,
            hasPendingRequest = hasPendingRequest,
            onSelectRoom = { selected -> onRoomSelected(selected, visibleRooms, availableRooms) }
        )
    }

    private fun onRoomSelected(room: Room, visibleRooms: List<Room>, availableRooms: List<Room>) {
        selectedRoomId = room._id
        bindRoomSelectionState(availableRooms)
        renderRooms(visibleRooms, availableRooms)
    }

    private fun bindRoomSelectionState(availableRooms: List<Room>) {
        val selectedRoom = availableRooms.firstOrNull { it._id == selectedRoomId }

        if (selectedRoom == null) {
                currentRoomText.text = "No room assigned yet"
                currentBookingMeta.text = if (availableRooms.isEmpty()) {
                    "No available rooms right now. Please wait for a room to open."
                } else {
                    "Select any available room below, then tap Book Request."
                }
                releaseRoomBtn.isEnabled = false
                releaseRoomBtn.text = if (availableRooms.isEmpty()) "No Rooms Available" else "Select A Room"
                releaseRoomBtn.setOnClickListener(null)
                return
        }

        currentRoomText.text = "Selected room: ${selectedRoom.roomNumber}"
        currentBookingMeta.text = "Floor ${selectedRoom.floor} | ${selectedRoom.occupiedBeds}/${selectedRoom.capacity} occupied. Tap Book Request to send this to admin."
        releaseRoomBtn.isEnabled = true
        releaseRoomBtn.text = "Book Request"
        releaseRoomBtn.setOnClickListener { requestRoom(selectedRoom) }
    }

    private fun buildVisibleRooms(
        availableRooms: List<Room>,
        currentRoom: Room?,
        pendingRoom: Room?
    ): List<Room> {
        val orderedRooms = mutableListOf<Room>()

        currentRoom?.let { orderedRooms.add(it) }
        pendingRoom?.takeIf { pending -> orderedRooms.none { it._id == pending._id } }?.let { orderedRooms.add(it) }

        availableRooms.forEach { room ->
            if (orderedRooms.none { it._id == room._id }) {
                orderedRooms.add(room)
            }
        }

        return orderedRooms
    }

    private fun bindPendingRequest(request: RoomRequestItem) {
        val room = request.preferredRoom
        currentRoomText.text = "Pending request submitted"
        currentBookingMeta.text = room?.let {
            "Preferred room ${it.roomNumber} on floor ${it.floor} is waiting for admin approval."
        } ?: "Waiting for admin approval."
        releaseRoomBtn.isEnabled = true
        releaseRoomBtn.text = "Cancel Request"
        releaseRoomBtn.setOnClickListener { cancelPendingRequest(request.id) }
    }

    private fun requestRoom(room: Room) {
        ApiClient.apiService.requestRoomBooking(
            RequestRoomBookingRequest(
                userId = sessionManager.getUserId(),
                preferredRoomId = room._id ?: ""
            )
        ).enqueue(object : Callback<RoomRequestResponse> {
            override fun onResponse(call: Call<RoomRequestResponse>, response: Response<RoomRequestResponse>) {
                if (response.isSuccessful) {
                    Toast.makeText(
                        this@UserDashboardActivity,
                        "Room request submitted",
                        Toast.LENGTH_SHORT
                    ).show()
                    selectedRoomId = null
                    fetchDashboard(silent = true)
                } else {
                    Toast.makeText(
                        this@UserDashboardActivity,
                        extractErrorMessage(response, "Unable to submit request"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<RoomRequestResponse>, t: Throwable) {
                Toast.makeText(
                    this@UserDashboardActivity,
                    "Error: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun submitComplaint() {
        val subject = complaintSubjectInput.text.toString().trim()
        val description = complaintDescriptionInput.text.toString().trim()

        if (subject.isBlank()) {
            complaintSubjectInput.error = "Subject required"
            return
        }

        if (description.isBlank()) {
            complaintDescriptionInput.error = "Description required"
            return
        }

        submitComplaintBtn.isEnabled = false
        submitComplaintBtn.text = "Submitting..."

        ApiClient.apiService.createComplaint(
            ComplaintRequest(
                userId = sessionManager.getUserId(),
                subject = subject,
                description = description
            )
        ).enqueue(object : Callback<ComplaintResponse> {
            override fun onResponse(call: Call<ComplaintResponse>, response: Response<ComplaintResponse>) {
                submitComplaintBtn.isEnabled = true
                submitComplaintBtn.text = "Submit Complaint"

                if (response.isSuccessful) {
                    complaintSubjectInput.text?.clear()
                    complaintDescriptionInput.text?.clear()
                    Toast.makeText(
                        this@UserDashboardActivity,
                        "Complaint submitted successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                    fetchDashboard(silent = true)
                } else {
                    Toast.makeText(
                        this@UserDashboardActivity,
                        extractErrorMessage(response, "Unable to submit complaint"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<ComplaintResponse>, t: Throwable) {
                submitComplaintBtn.isEnabled = true
                submitComplaintBtn.text = "Submit Complaint"
                Toast.makeText(
                    this@UserDashboardActivity,
                    "Error: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun cancelPendingRequest(requestId: String) {
        ApiClient.apiService.cancelRoomRequest(requestId)
            .enqueue(object : Callback<RoomRequestResponse> {
                override fun onResponse(call: Call<RoomRequestResponse>, response: Response<RoomRequestResponse>) {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@UserDashboardActivity,
                            "Request cancelled successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                        fetchDashboard(silent = true)
                    } else {
                        Toast.makeText(
                            this@UserDashboardActivity,
                            extractErrorMessage(response, "Unable to cancel request"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<RoomRequestResponse>, t: Throwable) {
                    Toast.makeText(
                        this@UserDashboardActivity,
                        "Error: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun releaseRoom() {
        ApiClient.apiService.releaseRoom(sessionManager.getUserId())
            .enqueue(object : Callback<BookingResponse> {
                override fun onResponse(call: Call<BookingResponse>, response: Response<BookingResponse>) {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@UserDashboardActivity,
                            "Room released successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                        fetchDashboard(silent = true)
                    } else {
                        Toast.makeText(
                            this@UserDashboardActivity,
                            extractErrorMessage(response, "Unable to release room"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<BookingResponse>, t: Throwable) {
                    Toast.makeText(
                        this@UserDashboardActivity,
                        "Error: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun showUnreadNotifications(notifications: List<RoomNotification>) {
        val unreadAlerts = notifications.filter {
            !it.isRead &&
                (it.type == "room_assigned" || it.type == "admin_notice") &&
                !shownNotificationIds.contains(it.id)
        }

        unreadAlerts.forEach { notification ->
            shownNotificationIds.add(notification.id)
            NotificationHelper.showAlertNotification(
                context = this,
                title = notification.title,
                message = notification.message,
                notificationId = notification.id.hashCode()
            )
        }

        if (unreadAlerts.isNotEmpty()) {
            ApiClient.apiService.markNotificationsRead(sessionManager.getUserId())
                .enqueue(object : Callback<BookingResponse> {
                    override fun onResponse(call: Call<BookingResponse>, response: Response<BookingResponse>) = Unit
                    override fun onFailure(call: Call<BookingResponse>, t: Throwable) = Unit
                })
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                101
            )
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        val feeId = payingFeeId
        val orderId = paymentData?.orderId
        val paymentId = paymentData?.paymentId ?: razorpayPaymentId
        val signature = paymentData?.signature

        if (feeId.isNullOrBlank() || orderId.isNullOrBlank() || paymentId.isNullOrBlank() || signature.isNullOrBlank()) {
            restoreFeeButtonState()
            Toast.makeText(this, "Payment succeeded but verification data was incomplete", Toast.LENGTH_LONG).show()
            return
        }

        payFeeBtn.text = "Verifying Payment..."

        ApiClient.apiService.verifyPayment(
            VerifyPaymentRequest(
                feeId = feeId,
                razorpay_order_id = orderId,
                razorpay_payment_id = paymentId,
                razorpay_signature = signature
            )
        ).enqueue(object : Callback<PaymentVerificationResponse> {
            override fun onResponse(
                call: Call<PaymentVerificationResponse>,
                response: Response<PaymentVerificationResponse>
            ) {
                if (response.isSuccessful) {
                    Toast.makeText(
                        this@UserDashboardActivity,
                        "Fee paid successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                    payingFeeId = null
                    fetchDashboard(silent = true)
                } else {
                    restoreFeeButtonState()
                    Toast.makeText(
                        this@UserDashboardActivity,
                        extractErrorMessage(response, "Payment verification failed"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onFailure(call: Call<PaymentVerificationResponse>, t: Throwable) {
                restoreFeeButtonState()
                Toast.makeText(
                    this@UserDashboardActivity,
                    "Verification error: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        Log.e(TAG, "Razorpay payment error code=$code response=$response")
        restoreFeeButtonState()
        Toast.makeText(
            this,
            extractRazorpayErrorMessage(response),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun restoreFeeButtonState() {
        payingFeeId = null
        bindFeeState(currentFee)
    }

    private fun formatCurrency(amount: Double): String {
        return if (amount % 1.0 == 0.0) {
            "Rs ${amount.toInt()}"
        } else {
            String.format(Locale.US, "Rs %.2f", amount)
        }
    }

    private fun extractErrorMessage(response: Response<*>, fallback: String): String {
        return try {
            val rawBody = response.errorBody()?.string()
            if (rawBody.isNullOrBlank()) {
                fallback
            } else {
                gson.fromJson(rawBody, ApiErrorResponse::class.java)?.message ?: fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun extractRazorpayErrorMessage(response: String?): String {
        if (response.isNullOrBlank()) {
            return "Payment failed or cancelled"
        }

        return try {
            val error = JSONObject(response).optJSONObject("error")
            val description = error?.optString("description").orEmpty()

            if (description.isBlank() || description.equals("undefined", ignoreCase = true)) {
                "Payment failed or cancelled"
            } else {
                description
            }
        } catch (_: Exception) {
            response
        }
    }

    data class ApiErrorResponse(
        val message: String?
    )
}
