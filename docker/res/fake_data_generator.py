"""
Generates a CSV of fake products in the same format as products_lacoste_sample.csv,
used for testing the import job with large data sets.

Usage:
    pip install faker
    python fake_data_generator.py                # 200k records -> Fake_Product_data_200k.csv
    python fake_data_generator.py 2000000        # 2M records   -> Fake_Product_data_2m.csv
    python fake_data_generator.py 1000 -o small.csv --seed 7

The output is deterministic for a given record count and seed.
"""
import argparse
import csv
import sys

from faker import Faker

HEADERS = ["id", "title", "gender_id", "composition", "sleeve", "photo", "url"]
DEFAULT_RECORDS = 200_000
DEFAULT_SEED = 42


def default_filename(records):
    if records % 1_000_000 == 0:
        size = f"{records // 1_000_000}m"
    elif records % 1_000 == 0:
        size = f"{records // 1_000}k"
    else:
        size = str(records)
    return f"Fake_Product_data_{size}.csv"


def datagenerate(records, output, seed):
    Faker.seed(seed)
    fake = Faker('fr_FR')
    with open(output, 'w', newline='', encoding='utf-8') as csvFile:
        writer = csv.DictWriter(csvFile, fieldnames=HEADERS)
        writer.writeheader()

        for i in range(records):
            composition_element = fake.random_element(elements=('Coton', 'Elasthanne', 'Polyester', 'Laine', 'Polyamide', 'Lyocell'))
            composition = str(fake.random_int(min=1, max=100)) + '% ' + composition_element
            sleeve = fake.random_element(elements=('Manches courtes', 'Manches longues', 'Manches aux coudes'))

            writer.writerow({
                    "id" : fake.bothify('??###-##-?#?', letters='ABCDEFGHIJKLMNOPQRSTUVWXYZ'),
                    "title" : 'Polo ' + fake.word() + ' ' + fake.word(),
                    "gender_id": fake.random_element(elements=('MAN', 'WOM', 'BOY', 'GIR', 'UNI')),
                    "composition" : composition,
                    "sleeve" : sleeve,
                    "photo": fake.file_path(depth=3, extension='jpg'),
                    "url" : fake.image_url()
                    })


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Generate a CSV of fake products.")
    parser.add_argument("records", nargs="?", type=int, default=DEFAULT_RECORDS,
                        help=f"number of products to generate (default: {DEFAULT_RECORDS})")
    parser.add_argument("-o", "--output", help="output file (default: Fake_Product_data_<size>.csv)")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED,
                        help=f"random seed, same seed gives the same file (default: {DEFAULT_SEED})")
    args = parser.parse_args()

    output = args.output or default_filename(args.records)
    datagenerate(args.records, output, args.seed)
    print(f"Generated {args.records} products into {output}", file=sys.stderr)
