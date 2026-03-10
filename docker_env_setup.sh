# Step 1: Stop all Docker containers
docker compose stop data-provider-app heatmap-ui-app stock-data-app

# Step 2: Remove old containers if any
docker rm data-provider-app
docker rm stock-data-app
docker rm heatmap-ui-app

# Step 3: Maven install and Docker build
cd project-data-provider
mvn clean install -DskipTests
docker build -t project-data-provider:0.0.1 -f Dockerfile .
cd ..

cd project-stock-data
mvn clean install -DskipTests
docker build -t project-stock-data:0.0.1 -f Dockerfile .
cd ..

cd project-heatmap-ui
mvn clean install -DskipTests
docker build -t project-heatmap-ui:0.0.1 -f Dockerfile .
cd ..

# Step 4: Docker run (using docker-compose)
docker compose up -d
