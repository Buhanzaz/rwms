pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "rwms"

include("platform:architecture-tests")
include("platform:technical-contracts")
include("platform:spring-boot-starter")
include("services:auth-service")
include("services:task-board-service")
include("services:api-gateway-service")
include("services:warehouse-service")
include("services:asset-service")
include("services:maintenance-service")
include("services:inventory-service")
include("services:logistics-service")
