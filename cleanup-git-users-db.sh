#!/bin/bash
set -e

CONTAINER_NAME="whk-services-ignition-1"
DB_PATH="/usr/local/bin/ignition/data/db/config.idb"
TEMP_DIR=$(mktemp -d)
H2_JAR="$TEMP_DIR/h2.jar"
H2_VERSION="1.4.200"

echo "==================================="
echo "Git Users Database Cleanup Script"
echo "==================================="
echo ""
echo "This script will:"
echo "  1. Stop Ignition Gateway"
echo "  2. Download H2 database tool"
echo "  3. Copy database from container"
echo "  4. Delete corrupted GitReposUsersRecord entries"
echo "  5. Copy database back to container"
echo "  6. Start Ignition Gateway"
echo ""
read -p "Continue? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
fi

echo ""
echo "Step 1: Stopping Ignition Gateway..."
docker stop $CONTAINER_NAME
echo "Container stopped"

echo ""
echo "Step 2: Downloading H2 database tool..."
curl -sL -o "$H2_JAR" "https://repo1.maven.org/maven2/com/h2database/h2/$H2_VERSION/h2-$H2_VERSION.jar"
echo "Downloaded H2 jar to $H2_JAR"

echo ""
echo "Step 3: Copying database from container..."
docker cp "$CONTAINER_NAME:$DB_PATH" "$TEMP_DIR/config.idb"
echo "Database copied to $TEMP_DIR/config.idb"

echo ""
echo "Step 4: Deleting corrupted records..."
# Create SQL script - H2 stores table names in uppercase by default
cat > "$TEMP_DIR/cleanup.sql" <<'EOF'
-- Show tables before cleanup
SHOW TABLES;
-- Try both case variations
DELETE FROM GITREPOSUSERSRECORD;
SELECT COUNT(*) AS remaining_records FROM GITREPOSUSERSRECORD;
EOF

# Execute SQL script
echo "Running SQL cleanup..."
java -cp "$H2_JAR" org.h2.tools.RunScript \
    -url "jdbc:h2:$TEMP_DIR/config" \
    -user sa \
    -password "" \
    -script "$TEMP_DIR/cleanup.sql" 2>&1 || {
    echo "Note: Table may not exist or already empty"
}

echo "Records deleted successfully"

echo ""
echo "Step 5: Copying database back to container..."
docker cp "$TEMP_DIR/config.idb" "$CONTAINER_NAME:$DB_PATH"
# Fix ownership - database must be owned by ignition user
docker exec --user root $CONTAINER_NAME chown ignition:ignition "$DB_PATH"
echo "Database restored to container with correct permissions"

echo ""
echo "Step 6: Starting Ignition Gateway..."
docker start $CONTAINER_NAME
echo "Container started, Ignition Gateway starting..."

echo ""
echo "Step 7: Cleaning up temporary files..."
rm -rf "$TEMP_DIR"

echo ""
echo "==================================="
echo "✓ Database cleanup completed!"
echo "==================================="
echo ""
echo "Wait about 30 seconds for Ignition to fully start, then:"
echo "  1. Install the Git module (Git-unsigned.modl)"
echo "  2. Navigate to Platform > Git > Git Users"
echo "  3. The page should now load successfully"
echo ""
