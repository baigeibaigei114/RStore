from clients.callback_client import CallbackClient
from clients.minio_client import MinioStorageClient
from config import load_settings
from processors.change_processor import ChangeDetectionProcessor
from processors.ndvi_processor import NdviProcessor
from processors.ndwi_processor import NdwiProcessor
from workers.rabbitmq_consumer import RabbitMqConsumer


def main() -> None:
    settings = load_settings()
    settings.worker.temp_dir.mkdir(parents=True, exist_ok=True)

    storage_client = MinioStorageClient(settings.minio)
    callback_client = CallbackClient(settings.callback)

    processors = {
        "NDVI": NdviProcessor(storage_client, settings.worker.temp_dir),
        "NDWI": NdwiProcessor(storage_client, settings.worker.temp_dir),
        "CHANGE_DETECTION": ChangeDetectionProcessor(storage_client, settings.worker.temp_dir),
    }

    consumer = RabbitMqConsumer(settings.rabbitmq, callback_client, processors)
    consumer.start()


if __name__ == "__main__":
    main()
