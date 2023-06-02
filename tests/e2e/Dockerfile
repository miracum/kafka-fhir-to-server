FROM docker.io/library/python:3.11-alpine@sha256:af8fef83397b3886ed93d2c81bf3b4e70d39c0789c1c6feb1ecb86ca9bc42a0a
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /opt/tests

COPY requirements.txt .
RUN pip install --require-hashes --no-cache-dir -r requirements.txt

COPY . .

USER 65534:65534
ENTRYPOINT ["pytest"]
CMD [ "-p", "no:cacheprovider", "test_expected_number_of_resources_in_server.py" ]
