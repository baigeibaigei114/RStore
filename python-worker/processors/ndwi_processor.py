from pathlib import Path

from clients.minio_client import MinioStorageClient
from processors.spectral_index_processor import SpectralIndexProcessor


class NdwiProcessor:
    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path):
        self._processor = SpectralIndexProcessor(storage_client, temp_dir, "NDWI")

    def process(self, message: dict) -> dict:
        params = message.get("params") or {}
        green_band = int(params.get("greenBand", 2))
        nir_band = int(params.get("nirBand", 4))
        # NDWI = (GREEN - NIR) / (GREEN + NIR + 1e-6)
        return self._processor.process_index(
            message,
            first_band=green_band,
            second_band=nir_band,
            band_names={"greenBand": green_band, "nirBand": nir_band},
        )
