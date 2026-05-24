const path = require("path");
const bcrypt = require("bcryptjs");
const dotenv = require("dotenv");
const mongoose = require("mongoose");
const User = require("../models/User");

dotenv.config({ path: path.join(__dirname, "..", ".env") });

const createAdmin = async () => {
  const name = process.env.CREATE_ADMIN_NAME || process.env.ADMIN_NAME || "System Admin";
  const email = (process.env.CREATE_ADMIN_EMAIL || process.env.ADMIN_EMAIL || "").trim().toLowerCase();
  const password = process.env.CREATE_ADMIN_PASSWORD || process.env.ADMIN_PASSWORD;

  if (!email || !password) {
    throw new Error("CREATE_ADMIN_EMAIL and CREATE_ADMIN_PASSWORD are required.");
  }

  await mongoose.connect(process.env.MONGO_URI, {
    serverSelectionTimeoutMS: 10000
  });

  const hashedPassword = await bcrypt.hash(password, 10);

  await User.findOneAndUpdate(
    { email },
    {
      name,
      email,
      password: hashedPassword,
      role: "admin"
    },
    {
      new: true,
      upsert: true,
      setDefaultsOnInsert: true
    }
  );

  console.log(`Admin account ready: ${email}`);
};

createAdmin()
  .then(() => mongoose.disconnect())
  .catch((error) => {
    console.error("Admin creation failed:", error.message);
    mongoose.disconnect().finally(() => process.exit(1));
  });
