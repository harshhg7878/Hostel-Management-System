const crypto = require("crypto");
const https = require("https");
const Fee = require("../models/Fee");
const User = require("../models/User");

const serializeFee = (fee) => {
  if (!fee) {
    return null;
  }

  const resolvedUser = fee.user && typeof fee.user === "object" && fee.user._id
    ? {
        id: fee.user._id,
        name: fee.user.name,
        email: fee.user.email,
        role: fee.user.role
      }
    : null;

  return {
    id: fee._id,
    userId: fee.user?._id || fee.user,
    user: resolvedUser,
    title: fee.title,
    monthLabel: fee.monthLabel,
    amount: fee.amount,
    currency: fee.currency,
    status: fee.status,
    dueDate: fee.dueDate,
    receipt: fee.receipt,
    gateway: fee.gateway,
    gatewayOrderId: fee.gatewayOrderId,
    gatewayPaymentId: fee.gatewayPaymentId,
    paidSource: fee.paidSource,
    adminNote: fee.adminNote,
    paidAt: fee.paidAt,
    createdAt: fee.createdAt,
    updatedAt: fee.updatedAt
  };
};

const emitRealtimeEvent = (req, eventName, payload = {}) => {
  const io = req.app.get("io");

  if (io) {
    io.emit(eventName, payload);
  }
};

const emitFeeEvents = (req, userId) => {
  emitRealtimeEvent(req, "feesUpdated", { userId: String(userId) });
  emitRealtimeEvent(req, "adminPaymentsUpdated", { userId: String(userId) });
};

const buildReceipt = (userId, monthLabel) => {
  const normalizedMonth = monthLabel.replace(/[^a-zA-Z0-9]/g, "").slice(0, 12) || "fee";
  return `fee_${String(userId).slice(-6)}_${normalizedMonth}_${Date.now()}`.slice(0, 40);
};

const hasValidRazorpayKeys = (keyId, keySecret) => {
  return Boolean(
    keyId &&
    keySecret &&
    /^rzp_(test|live)_/.test(keyId) &&
    keySecret !== "replace-with-razorpay-secret"
  );
};

const callRazorpayOrdersApi = (payload) => {
  return new Promise((resolve, reject) => {
    const keyId = process.env.RAZORPAY_KEY_ID;
    const keySecret = process.env.RAZORPAY_KEY_SECRET;

    if (!hasValidRazorpayKeys(keyId, keySecret)) {
      reject(new Error("Valid Razorpay keys are missing in backend .env"));
      return;
    }

    const body = JSON.stringify(payload);
    const auth = Buffer.from(`${keyId}:${keySecret}`).toString("base64");

    const request = https.request(
      {
        hostname: "api.razorpay.com",
        path: "/v1/orders",
        method: "POST",
        headers: {
          Authorization: `Basic ${auth}`,
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(body)
        }
      },
      (response) => {
        let data = "";

        response.on("data", (chunk) => {
          data += chunk;
        });

        response.on("end", () => {
          try {
            const parsed = JSON.parse(data || "{}");

            if (response.statusCode && response.statusCode >= 200 && response.statusCode < 300) {
              resolve(parsed);
              return;
            }

            reject(new Error(parsed.error?.description || "Unable to create Razorpay order"));
          } catch (error) {
            reject(new Error("Invalid response from Razorpay"));
          }
        });
      }
    );

    request.on("error", (error) => reject(error));
    request.write(body);
    request.end();
  });
};

const createFee = async (req, res) => {
  try {
    const { userId, title, monthLabel, amount, dueDate } = req.body;

    if (!userId || !title || !monthLabel || !amount) {
      return res.status(400).json({ message: "User, title, month label and amount are required" });
    }

    const user = await User.findById(userId).select("-password");

    if (!user) {
      return res.status(404).json({ message: "User not found" });
    }

    const fee = await Fee.create({
      user: user._id,
      title,
      monthLabel,
      amount,
      dueDate: dueDate || null,
      receipt: buildReceipt(user._id, monthLabel)
    });

    const populatedFee = await Fee.findById(fee._id).populate("user", "-password");

    emitFeeEvents(req, user._id);

    res.status(201).json({
      message: "Fee created successfully",
      fee: serializeFee(populatedFee)
    });
  } catch (error) {
    res.status(500).json({ message: "Server error", error: error.message });
  }
};

const getUserFees = async (req, res) => {
  try {
    const { userId } = req.params;
    const canAccess = req.user && (
      String(req.user._id) === String(userId) ||
      req.user.role === "admin"
    );

    if (!canAccess) {
      return res.status(403).json({ message: "Forbidden" });
    }

    const fees = await Fee.find({ user: userId }).sort({ createdAt: -1 });

    res.status(200).json(fees.map(serializeFee));
  } catch (error) {
    res.status(500).json({ message: "Server error", error: error.message });
  }
};

const getAllFees = async (req, res) => {
  try {
    const fees = await Fee.find()
      .populate("user", "-password")
      .sort({ createdAt: -1 });

    res.status(200).json(fees.map(serializeFee));
  } catch (error) {
    res.status(500).json({ message: "Server error", error: error.message });
  }
};

const createPaymentOrder = async (req, res) => {
  try {
    const { userId, feeId } = req.body;
    const effectiveUserId = req.user?.role === "student" ? String(req.user._id) : userId;

    if (!effectiveUserId) {
      return res.status(400).json({ message: "User is required" });
    }

    const user = await User.findById(effectiveUserId).select("-password");

    if (!user) {
      return res.status(404).json({ message: "User not found" });
    }

    const fee = feeId
      ? await Fee.findOne({ _id: feeId, user: effectiveUserId })
      : await Fee.findOne({ user: effectiveUserId, status: { $in: ["pending", "failed"] } }).sort({ createdAt: -1 });

    if (!fee) {
      return res.status(404).json({ message: "No pending fee found for this user" });
    }

    if (fee.status === "paid") {
      return res.status(400).json({ message: "This fee has already been paid" });
    }

    const receipt = fee.receipt || buildReceipt(user._id, fee.monthLabel);
    const order = await callRazorpayOrdersApi({
      amount: Math.round(Number(fee.amount) * 100),
      currency: fee.currency,
      receipt,
      notes: {
        feeId: String(fee._id),
        userId: String(user._id),
        monthLabel: fee.monthLabel
      }
    });

    fee.receipt = receipt;
    fee.gatewayOrderId = order.id;
    fee.status = "pending";
    fee.paidSource = "razorpay";
    await fee.save();

    res.status(200).json({
      message: "Payment order created successfully",
      keyId: process.env.RAZORPAY_KEY_ID,
      orderId: order.id,
      amount: order.amount,
      currency: order.currency,
      fee: serializeFee(fee),
      user: {
        name: user.name,
        email: user.email
      }
    });
  } catch (error) {
    res.status(502).json({ message: error.message || "Unable to create payment order" });
  }
};

const verifyPayment = async (req, res) => {
  try {
    const {
      feeId,
      razorpay_order_id: razorpayOrderId,
      razorpay_payment_id: razorpayPaymentId,
      razorpay_signature: razorpaySignature
    } = req.body;

    if (!feeId || !razorpayOrderId || !razorpayPaymentId || !razorpaySignature) {
      return res.status(400).json({ message: "Fee id and Razorpay payment details are required" });
    }

    const fee = await Fee.findById(feeId);

    if (!fee) {
      return res.status(404).json({ message: "Fee not found" });
    }

    const expectedSignature = crypto
      .createHmac("sha256", process.env.RAZORPAY_KEY_SECRET || "")
      .update(`${razorpayOrderId}|${razorpayPaymentId}`)
      .digest("hex");

    if (expectedSignature !== razorpaySignature) {
      fee.status = "failed";
      await fee.save();
      return res.status(400).json({ message: "Payment signature verification failed" });
    }

    fee.status = "paid";
    fee.gatewayOrderId = razorpayOrderId;
    fee.gatewayPaymentId = razorpayPaymentId;
    fee.gatewaySignature = razorpaySignature;
    fee.paidSource = "razorpay";
    fee.paidAt = new Date();
    await fee.save();

    emitFeeEvents(req, fee.user);

    res.status(200).json({
      message: "Payment verified successfully",
      fee: serializeFee(fee)
    });
  } catch (error) {
    res.status(500).json({ message: "Unable to verify payment", error: error.message });
  }
};

const updateFeeByAdmin = async (req, res) => {
  try {
    const { feeId } = req.params;
    const { status, adminNote, dueDate } = req.body;

    const fee = await Fee.findById(feeId).populate("user", "-password");

    if (!fee) {
      return res.status(404).json({ message: "Fee not found" });
    }

    if (status && !["pending", "paid", "failed"].includes(status)) {
      return res.status(400).json({ message: "Invalid fee status" });
    }

    if (status) {
      fee.status = status;

      if (status === "paid") {
        fee.paidAt = fee.paidAt || new Date();
        fee.paidSource = "manual";
      }

      if (status === "pending") {
        fee.paidAt = null;
        fee.gatewayPaymentId = "";
        fee.gatewaySignature = "";
        fee.paidSource = "razorpay";
      }

      if (status === "failed") {
        fee.paidAt = null;
      }
    }

    if (typeof adminNote === "string") {
      fee.adminNote = adminNote.trim();
    }

    if (dueDate !== undefined) {
      fee.dueDate = dueDate ? new Date(dueDate) : null;
    }

    await fee.save();

    const updatedFee = await Fee.findById(fee._id).populate("user", "-password");
    emitFeeEvents(req, fee.user._id);

    res.status(200).json({
      message: "Fee updated successfully",
      fee: serializeFee(updatedFee)
    });
  } catch (error) {
    res.status(500).json({ message: "Unable to update fee", error: error.message });
  }
};

module.exports = {
  serializeFee,
  createFee,
  getAllFees,
  getUserFees,
  createPaymentOrder,
  verifyPayment,
  updateFeeByAdmin
};
