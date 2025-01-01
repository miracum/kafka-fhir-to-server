FROM docker.io/library/python:3.13-alpine@sha256:657dbdb20479a6523b46c06114c8fec7db448232f956a429d3cc0606d30c1b59
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /opt/tests

COPY requirements.txt .
RUN pip install --require-hashes --no-cache-dir -r requirements.txt

COPY . .

USER 65534:65534
ENTRYPOINT ["pytest"]
CMD [ "-p", "no:cacheprovider", "test_expected_number_of_resources_in_server.py" ]
