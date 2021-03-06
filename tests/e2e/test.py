import logging
import os

import pytest
import requests
from fhirclient import client
from fhirclient.models.domainresource import DomainResource
from fhirclient.models.encounter import Encounter
from fhirclient.models.observation import Observation
from fhirclient.models.patient import Patient
from requests.adapters import HTTPAdapter
from retrying import retry
from urllib3.util.retry import Retry

EXPECTED_RESOURCE_COUNTS = [
    (Patient, 2),
    (Observation, 1),
    (Encounter, 2),
]


LOG = logging.getLogger(__name__)

FHIR_SERVER_URL = os.environ.get("FHIR_SERVER_URL", "http://localhost:8082/fhir")


@pytest.fixture(scope="session", autouse=True)
def wait_for_server_to_be_up(request):
    s = requests.Session()
    retries = Retry(total=15, backoff_factor=5, status_forcelist=[502, 503, 504])
    s.mount("http://", HTTPAdapter(max_retries=retries))

    print(f"Using FHIR server @ {FHIR_SERVER_URL}")

    response = s.get(
        f"{FHIR_SERVER_URL}/metadata",
    )

    if response.status_code != 200:
        pytest.fail("Failed to wait for server to be up")


@pytest.fixture
def smart():
    settings = {
        "app_id": "kafka-fhir-to-server-e2e-test",
        "api_base": FHIR_SERVER_URL,
    }
    smart = client.FHIRClient(settings=settings)
    return smart


@retry(
    wait_exponential_multiplier=1_000,
    wait_exponential_max=10_000,
    stop_max_delay=600_000,
)
@pytest.mark.parametrize("resource,expected_count", EXPECTED_RESOURCE_COUNTS)
def test_has_created_expected_number_of_resources(
    resource: DomainResource, expected_count: int, smart
):
    search = resource.where(struct={"_summary": "count"})
    result = search.perform(smart.server)
    assert result.total >= expected_count
