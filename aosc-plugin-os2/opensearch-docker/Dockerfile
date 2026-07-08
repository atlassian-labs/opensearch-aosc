ARG OPENSEARCH_VERSION
FROM opensearchproject/opensearch:${OPENSEARCH_VERSION}

# Install the AOSC plugin from the build output
COPY opensearch-aosc.zip /tmp/opensearch-aosc.zip
RUN /usr/share/opensearch/bin/opensearch-plugin install --batch file:///tmp/opensearch-aosc.zip
