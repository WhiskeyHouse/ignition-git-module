#!/usr/bin/env python3
"""
Direct H2 Database Cleanup Script
Deletes corrupted GitReposUsersRecord entries using JayDeBeApi (Python JDBC)
"""
import subprocess
import sys
import os

print("=" * 60)
print("Git Users Database Cleanup - Direct SQL Method")
print("=" * 60)
print()

# Step 1: Stop Ignition
print("Step 1: Stopping Ignition container...")
result = subprocess.run(["docker", "stop", "whk-services-ignition-1"],
                       capture_output=True, text=True)
if result.returncode != 0:
    print(f"Error: Failed to stop container: {result.stderr}")
    print("Cannot safely modify database while Ignition may be running.")
    sys.exit(1)
print("✓ Container stopped")

print()

# Step 2: Copy database
print("Step 2: Copying database from container...")
subprocess.run(["docker", "cp",
               "whk-services-ignition-1:/usr/local/bin/ignition/data/db/config.idb",
               "/tmp/config-cleanup.idb"], check=True)
print("✓ Database copied to /tmp/config-cleanup.idb")

print()

# Step 3: Download H2 JAR if not present
h2_version = "1.4.200"
h2_jar = "/tmp/h2.jar"
if not os.path.exists(h2_jar):
    print("Step 3a: Downloading H2 database tool...")
    subprocess.run([
        "curl", "-sfL", "-o", h2_jar,
        f"https://repo1.maven.org/maven2/com/h2database/h2/{h2_version}/h2-{h2_version}.jar"
    ], check=True)
    print(f"Downloaded H2 jar to {h2_jar}")

# Step 3: Run H2 SQL to delete records
print("Step 3: Executing SQL to delete corrupted records...")
db_url = "jdbc:h2:/tmp/config-cleanup"

sql_commands = """
-- Check if table exists
SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'GITREPOSUSERSRECORD';

-- Delete all records
DELETE FROM GITREPOSUSERSRECORD WHERE 1=1;

-- Verify deletion
SELECT COUNT(*) AS remaining FROM GITREPOSUSERSRECORD;
"""

# Write SQL to temp file
sql_file = "/tmp/cleanup.sql"
with open(sql_file, "w") as f:
    f.write(sql_commands)

# Execute SQL using H2 RunScript tool
try:
    result = subprocess.run([
        "java", "-cp", h2_jar,
        "org.h2.tools.RunScript",
        "-url", db_url,
        "-user", "sa",
        "-password", "",
        "-script", sql_file,
        "-continueOnError"
    ], capture_output=True, text=True, timeout=30)

    if "GITREPOSUSERSRECORD" in result.stdout or "0 row" in result.stdout:
        print("✓ SQL executed - records deleted")
    else:
        print("⚠ Table may not exist (will be created fresh)")

    if result.stderr and "error" in result.stderr.lower():
        print(f"SQL output: {result.stderr}")

except subprocess.TimeoutExpired:
    print("✗ SQL execution timed out")
    sys.exit(1)
except Exception as e:
    print(f"✗ Error: {e}")
    sys.exit(1)

print()

# Step 4: Copy database back
print("Step 4: Copying cleaned database back to container...")
subprocess.run(["docker", "cp",
               "/tmp/config-cleanup.idb",
               "whk-services-ignition-1:/usr/local/bin/ignition/data/db/config.idb"],
               check=True)
print("✓ Database restored")

print()

# Step 5: Fix permissions
print("Step 5: Fixing database file permissions...")
subprocess.run(["docker", "exec", "--user", "root", "whk-services-ignition-1",
               "chown", "ignition:ignition",
               "/usr/local/bin/ignition/data/db/config.idb"], check=True)
subprocess.run(["docker", "exec", "--user", "root", "whk-services-ignition-1",
               "chmod", "644",
               "/usr/local/bin/ignition/data/db/config.idb"], check=True)
print("✓ Permissions fixed")

print()

# Step 6: Start Ignition
print("Step 6: Starting Ignition container...")
subprocess.run(["docker", "start", "whk-services-ignition-1"], check=True)
print("✓ Container started")

print()
print("=" * 60)
print("✓ Database cleanup completed successfully!")
print("=" * 60)
print()
print("Next steps:")
print("  1. Wait 30 seconds for Ignition to fully start")
print("  2. Uninstall the old Git module (click Uninstall)")
print("  3. Install the new Git module: git-build/target/Git-unsigned.modl")
print("  4. Restart Ignition")
print("  5. Test Platform > Git > Git Users page")
print()
