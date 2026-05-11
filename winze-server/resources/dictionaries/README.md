# Bundled Dictionaries

This directory holds the wordlists used by the live spellcheck feature
([`src/llm_memory/ui/spellcheck.clj`](../../src/llm_memory/ui/spellcheck.clj)).

## en_US.txt

- **Source**: [`dwyl/english-words`](https://github.com/dwyl/english-words)
  — `words_alpha.txt` (letters-only English wordlist, ~370k entries
  including plurals and common inflections).
- **License**: [Unlicense](https://github.com/dwyl/english-words/blob/master/LICENSE.md)
  (public domain dedication).
- **Why not `/usr/share/dict/words`**: the macOS default is Webster's
  1934 `web2`, which is missing modern common words (e.g. "has",
  "words", "inline") and flags them as misspellings.
- **Transform applied**:
  1. Strip CRLF → LF.
  2. `tolower` every line.
  3. Drop any line that does not match `^[a-z]+$` (the spellcheck
     tokeniser splits on apostrophes so contractions are not needed
     in the wordlist).
  4. Deduplicate.
  5. Sort ASCII-ascending.
- **Shape**: one word per line, UTF-8, LF line endings.
- **Size**: ~370 000 words, ~4 MB.

To regenerate, from the `winze-server/` directory:

```bash
curl -fsSL -o /tmp/words_alpha.txt \
  https://raw.githubusercontent.com/dwyl/english-words/master/words_alpha.txt
tr -d '\r' < /tmp/words_alpha.txt \
  | awk '{print tolower($0)}' \
  | grep -E '^[a-z]+$' \
  | awk '!seen[$0]++' \
  | sort \
  > resources/dictionaries/en_US.txt
```

The user dictionary at `~/.winze/spellcheck/user-dictionary.txt`
follows the same shape (one word per line, sorted) so it can be
concatenated, diffed, or edited with any text editor.
