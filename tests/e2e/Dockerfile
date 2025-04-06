FROM docker.io/library/python:3.13-alpine@sha256:323a717dc4a010fee21e3f1aac738ee10bb485de4e7593ce242b36ee48d6b352
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /opt/tests

COPY requirements.txt .
RUN pip install --require-hashes --no-cache-dir -r requirements.txt

COPY . .

USER 65534:65534
ENTRYPOINT ["pytest"]
CMD [ "-p", "no:cacheprovider", "test_expected_number_of_resources_in_server.py" ]
