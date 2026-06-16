## Summary

Describe the change and why it is needed.

## Compatibility

- [ ] I considered OpenSearch version compatibility.
- [ ] I updated docs for any user-visible API, behavior, configuration, or operational change.
- [ ] I kept public APIs and extension points backward compatible, or documented the break.

## Validation

List the commands you ran, for example:

```bash
./gradlew :aosc-plugin:fastCheck -Dopensearch.version=2.19.0
./gradlew :aosc-plugin:yamlRestTest -Dopensearch.version=2.19.0
./gradlew :aosc-plugin:itTest -Dopensearch.version=2.19.0
./gradlew :aosc-plugin:smokeTest2Nodes -Dopensearch.version=2.19.0
npm run docs:build
```

## Notes for reviewers

Call out risks, follow-up work, or areas that need careful review.
