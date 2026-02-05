#!/bin/bash
# Script to drop the corrupted GitReposUsersRecord table

echo "Stopping Ignition..."
docker stop whk-services-ignition-1 || { echo "Failed to stop container"; exit 1; }

echo "Copying database..."
docker cp whk-services-ignition-1:/usr/local/bin/ignition/data/db/config.idb /tmp/clean-db.idb || { echo "Failed to copy database"; exit 1; }

echo "Downloading H2 JAR if needed..."
H2_VERSION="1.4.200"
if [ ! -f /tmp/h2.jar ]; then
    curl -sfL -o /tmp/h2.jar "https://repo1.maven.org/maven2/com/h2database/h2/$H2_VERSION/h2-$H2_VERSION.jar"
    echo "Downloaded H2 jar"
fi

echo "Dropping GitReposUsersRecord table..."
cd /tmp
cat > drop-table.sql <<'SQL'
DROP TABLE IF EXISTS GITREPOSUSERSRECORD;
SQL

java -cp h2.jar org.h2.tools.RunScript \
  -url "jdbc:h2:/tmp/clean-db" \
  -user sa \
  -password "" \
  -script drop-table.sql \
  -continueOnError

echo "Copying database back..."
docker cp /tmp/clean-db.idb whk-services-ignition-1:/usr/local/bin/ignition/data/db/config.idb

echo "Fixing permissions..."
docker exec --user root whk-services-ignition-1 chown ignition:ignition /usr/local/bin/ignition/data/db/config.idb
docker exec --user root whk-services-ignition-1 chmod 644 /usr/local/bin/ignition/data/db/config.idb

echo "Starting Ignition..."
docker start whk-services-ignition-1

echo "Done! Wait 30 seconds for Ignition to start, then test Git Users page."
