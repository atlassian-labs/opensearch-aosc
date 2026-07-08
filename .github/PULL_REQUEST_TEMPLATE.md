## Summary

Describe the change and why it is needed.

## Compatibility

- [ ] I considered OpenSearch version compatibility.
- [ ] I updated docs for any user-visible API, behavior, configuration, or operational change.
- [ ] I kept public APIs and extension points backward compatible, or documented the break.

## Validation

List the commands you ran, for example:

```bash
./gradlew fastCheck -PopensearchVersion=3.6.0
./gradlew yamlRestTest -PopensearchVersion=3.6.0
./gradlew itTest -PopensearchVersion=3.6.0
./gradlew smokeTest2Nodes -PopensearchVersion=3.6.0
npm run docs:build
```

The OpenSearch version selects the line (2.x or 3.x); change it (e.g. `-PopensearchVersion=2.19.0`) to validate the other line.

## Notes for reviewers

Call out risks, follow-up work, or areas that need careful review.
