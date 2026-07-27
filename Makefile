KOTLINC ?= kotlinc
JAVA ?= java
NODE ?= node
JVM_TARGET ?= 11

APP_JAR := build/consumer-rebalance-lab.jar
TEST_JAR := build/model-test.jar

.PHONY: build test check run clean docker-check

build: $(APP_JAR)

$(APP_JAR): src/App.kt src/Model.kt
	mkdir -p build
	$(KOTLINC) src/App.kt src/Model.kt -include-runtime -jvm-target $(JVM_TARGET) -d $@

$(TEST_JAR): src/Model.kt tests/ModelTest.kt
	mkdir -p build
	$(KOTLINC) src/Model.kt tests/ModelTest.kt -include-runtime -jvm-target $(JVM_TARGET) -d $@

test: $(APP_JAR) $(TEST_JAR)
	$(JAVA) -jar $(TEST_JAR)
	sh tests/server_test.sh $(JAVA) $(APP_JAR)
	$(NODE) --check public/app.js

check: clean build test

run: $(APP_JAR)
	$(JAVA) -jar $(APP_JAR)

clean:
	rm -rf build

docker-check:
	docker build --tag consumer-rebalance-lab:test .
	docker run --rm consumer-rebalance-lab:test --json >/dev/null
	test "$$(docker inspect --format '{{.Config.User}}' consumer-rebalance-lab:test)" = "10001:10001"
