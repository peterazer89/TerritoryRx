const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_rep_1";
const BOB_UID = "bob_rep_2";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write HCPs or Visits", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("hcps").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("visits").get());
});

test("Authenticated user can create and read their own valid CloudHcp", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("hcps").doc("hcp_101").set({
      userId: ALICE_UID,
      targetId: "T-101",
      name: "Dr. Ahmed Hassan",
      specialty: "Cardiology",
      quarterlyTarget: 8,
      priorityScore: 85,
      zone: "Mohandessin",
      isActive: true,
      version: 1,
      isDeleted: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("hcps").where("userId", "==", ALICE_UID).get()
  );
});

test("Cross-user access is denied for HCPs and Visits", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc(BOB_UID).collection("hcps").doc("hcp_202").set({
      userId: BOB_UID,
      targetId: "T-202",
      name: "Dr. Mona Adel",
      specialty: "Neurology",
      quarterlyTarget: 8,
      priorityScore: 90,
      zone: "Dokki",
      isActive: true,
      version: 1,
      isDeleted: false,
      createdAt: now,
      updatedAt: now,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("hcps").doc("hcp_202").get());
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("hcps").where("userId", "==", BOB_UID).get()
  );
});

test("Shadow field or invalid enum in CloudVisit is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("visits").doc("visit_1").set({
      userId: ALICE_UID,
      hcpTargetId: "T-101",
      clinicLabel: "Clinic 1",
      plannedDateIso: "2026-10-29",
      status: "INVALID_STATUS",
      qualifiesForCoverage: true,
      notes: "Follow up",
      version: 1,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("visits").doc("visit_2").set({
      userId: ALICE_UID,
      hcpTargetId: "T-101",
      clinicLabel: "Clinic 1",
      plannedDateIso: "2026-10-29",
      status: "COMPLETED",
      qualifiesForCoverage: true,
      notes: "Follow up",
      version: 1,
      ghostField: "hack",
      createdAt: now,
      updatedAt: now,
    })
  );
});
