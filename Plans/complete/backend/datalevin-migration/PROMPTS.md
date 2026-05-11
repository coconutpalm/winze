I want to replicate our current Planning system database, but using Datalevin in Clojure.

Search the Plans system and the documentation under mcp/planning-tool.  Read its source code if necessary.  Search the web for the latest information on how to use vector embeddings with Datalevin.

I want to keep everything within a single JVM process; I don't want to communicate with an external server to generate vector embeddings, for example.

I want to have an abstraction layer around the data store layer so I could swap in Datahike when its ecosystem's vector embedding layer is mature.  (This database is superior for an agentic memory system since it preserves history.)

I want to have a filesystem watcher so that files are reindexed incrementally as they are created, updated, moved, and deleted.  A full reindex should remain an option, but ideally should never be necessary since when we move to Datalevin I want to be able to track changes over time across file renames, etc.


Create a CONTEXT/PLAN document pair describing the context for these changes and the necessary steps.

----

Here's some more information:  Datahike's ecosystem contains an (alpha-quality) vector database library: https://github.com/replikativ/proximum

Explore this option and update the documents accordingly.


----

Regarding MCP Server:

Eventually, I want the indexer to be a long-running JVM process.  If the best way to implement MCP is via stdio, let's use a small Babashka program that accepts an MCP request and proxies it to the plan server (starting it if necessary).

Update the context/plan as needed.

