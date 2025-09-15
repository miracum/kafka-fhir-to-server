FROM docker.io/library/python:3.13-alpine@sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /opt/tests

COPY requirements.txt .
RUN pip install --require-hashes --no-cache-dir -r requirements.txt

COPY . .

USER 65534:65534
ENTRYPOINT ["pytest"]
CMD [ "-p", "no:cacheprovider", "test_expected_number_of_resources_in_server.py" ]
