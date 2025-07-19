FROM docker.io/library/python:3.13-alpine@sha256:37b14db89f587f9eaa890e4a442a3fe55db452b69cca1403cc730bd0fbdc8aaf
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /opt/tests

COPY requirements.txt .
RUN pip install --require-hashes --no-cache-dir -r requirements.txt

COPY . .

USER 65534:65534
ENTRYPOINT ["pytest"]
CMD [ "-p", "no:cacheprovider", "test_expected_number_of_resources_in_server.py" ]
