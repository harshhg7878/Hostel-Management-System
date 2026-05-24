const bcrypt = require("bcryptjs");
const User = require("../models/User");

const seedAdmin = async () => {
  const adminEmail = (process.env.ADMIN_EMAIL || "").trim().toLowerCase();
  const adminPassword = process.env.ADMIN_PASSWORD;
  const adminName = process.env.ADMIN_NAME || "System Admin";

  if (!adminEmail || !adminPassword) {
    console.log("Skipping admin seed because ADMIN_EMAIL or ADMIN_PASSWORD is not configured.");
    return;
  }

  const existingAdmin = await User.findOne({ email: adminEmail });

  if (existingAdmin) {
    return;
  }

  const hashedPassword = await bcrypt.hash(adminPassword, 10);

  await User.create({
    name: adminName,
    email: adminEmail,
    password: hashedPassword,
    role: "admin"
  });

  console.log(`Default admin created: ${adminEmail}`);
};

module.exports = seedAdmin;
