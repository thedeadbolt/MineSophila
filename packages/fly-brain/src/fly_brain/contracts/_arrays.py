"""Small array helpers shared by immutable data contracts."""

import numpy as np
from numpy.typing import NDArray


def readonly[T: np.generic](array: NDArray[T]) -> NDArray[T]:
    result = np.array(array, copy=True)
    result.setflags(write=False)
    return result
