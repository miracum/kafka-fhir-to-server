FROM python:3.11-alpine

# hadolint ignore=DL3018
RUN apk --no-cache add git

WORKDIR /opt/tests
COPY requirements.txt .
RUN pip install --require-hashes --no-cache-dir -r requirements.txt
COPY . .

USER 65534:65534
ENTRYPOINT ["pytest"]
CMD [ "test_expected_number_of_resources_in_server.py" ]
