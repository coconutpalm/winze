.PHONY: clean jar install deploy test repl

clean:
	clj -T:build clean

jar:
	clj -T:build jar

install:
	clj -T:build install

## Publish to Clojars (requires CLOJARS_USERNAME / CLOJARS_PASSWORD env vars)
deploy:
	clj -T:build deploy

test:
	clj -X:test

repl:
	clojure -M:dev
