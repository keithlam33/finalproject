# Step 1: Stop all Docker containers
docker compose stop data-provider-app stock-data-app stock-frontend-app

# Step 2: Remove old containers if any
docker rm data-provider-app
docker rm stock-data-app
docker rm stock-frontend-app

# Step 3: Maven install and Docker build
cd project-data-provider
mvn clean install -DskipTests
docker build -t project-data-provider:0.0.1 -f Dockerfile .
cd ..

cd project-stock-data
mvn clean install -DskipTests
docker build -t project-stock-data:0.0.1 -f Dockerfile .
cd ..

cd project-stock-frontend
docker build -t project-stock-frontend:0.0.1 -f Dockerfile .
cd ..

# Step 4: Docker run (using docker-compose)
docker compose up -d
