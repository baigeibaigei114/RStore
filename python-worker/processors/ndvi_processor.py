from pathlib import Path

from clients.minio_client import MinioStorageClient
from processors.spectral_index_processor import SpectralIndexProcessor


class NdviProcessor:
    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path):
        self._processor = SpectralIndexProcessor(storage_client, temp_dir, "NDVI")

    def process(self, message: dict) -> dict:
        params = message.get("params") or {}
        red_band = int(params.get("redBand", 3))
        nir_band = int(params.get("nirBand", 4))
        # NDVI = (NIR - RED) / (NIR + RED + 1e-6)
        return self._processor.process_index(
            message,
            first_band=nir_band,
            second_band=red_band,
            band_names={"redBand": red_band, "nirBand": nir_band},
        )
